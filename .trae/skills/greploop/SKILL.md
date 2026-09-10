---
name: "greploop"
description: "Iteratively improve a PR/MR/CL until Greptile gives 5/5 confidence with zero unresolved comments. Invoke when user wants to fully optimize a PR against Greptile review, or says 'greploop', 'fix review', 'reach 5/5', or pastes a Greptile review URL."
---

# Greploop

Iteratively fix a PR/MR/CL until Greptile gives a perfect review: 5/5 confidence, zero unresolved comments.

Adapted from https://github.com/greptileai/skills/blob/main/greploop/SKILL.md for the TRAE agent environment. Shell steps use the RunCommand tool; PR/review data uses WebFetch + GitHub CLI (`gh`); code fixes use Grep/Read/Edit; pushes use git over HTTPS with a token.

## Inputs

- **PR/MR/CL number or URL** (optional): If not provided, detect the PR for the current branch.
- **GitHub token**: If `gh` is not authenticated, user may provide a PAT. Use it via `git push https://<token>@github.com/<owner>/<repo>.git <branch>` and for `gh` calls via `GH_TOKEN=<token> gh ...`.

## TRAE tool mapping

| SKILL step | TRAE tool |
|---|---|
| Shell / git / gh / curl | RunCommand (non-interactive, auto-confirm) |
| Fetch PR/review HTML | WebFetch |
| Search codebase | Grep / Glob / SearchCodebase |
| Read files | Read |
| Edit files | Edit / Write |
| Hand back to user (login/CAPTCHA) | browser_waiting_for_user_interaction |

## Instructions

### 0. Detect platform

Check `git remote get-url origin` via RunCommand. Map:
- contains `github.com` → VCS=github
- contains `gitlab` or self-hosted GitLab (user override `--vcs gitlab`) → VCS=gitlab
- `p4 info` succeeds → VCS=perforce

Only GitHub is fully supported in this TRAE port. For GitLab/Perforce, fall back to the original SKILL's bash and adapt.

### 1. Identify the PR/MR/CL

**GitHub:**
- If user gave a PR number or URL, use it.
- Else detect from current branch: `gh pr view --json number,headRefName` (or with token: `GH_TOKEN=<token> gh pr view ...`).
- Confirm on the PR branch: `git checkout <headRefName>` if needed.

### 2. Loop (max 5 iterations)

#### A. Trigger Greptile review

1. Push any local changes: `git push` (or with token URL).
2. Wait ~30s for checks to start (use RunCommand `sleep 30` or just proceed and poll).
3. Check if Greptile check already running:
   `gh pr checks <PR> --json name,state` — look for name matching `greptile`.
4. If not running, request a review by commenting `@greptile review`:
   `gh pr comment <PR> --body "@greptile review"`.
5. Poll the Greptile check run until completed (max ~10 min). Use:
   `gh api repos/<owner>/<repo>/commits/<HEAD_SHA>/check-runs` and filter for greptile. Poll every ~15s.
   - If no `gh` auth, use WebFetch on `https://github.com/<owner>/<repo>/pull/<PR>` and look for the review bot comment to appear/update.

#### B. Fetch Greptile review results

Greptile may surface results in multiple places — check ALL:
1. PR description body: `gh pr view <PR> --json body`
2. General PR comments (issue comments): `gh api --paginate repos/<owner>/<repo>/issues/<PR>/comments` — filter for `greptile-apps[bot]` / `greptile-apps-staging[bot]`, use the most recently **updated** comment.
3. PR reviews: `gh api repos/<owner>/<repo>/pulls/<PR>/reviews` — most recent greptile entry.
4. Inline review comments: `gh api repos/<owner>/<repo>/pulls/<PR>/comments`.

If `gh` is unavailable/unauthenticated, use WebFetch on the PR URL and any review-specific URL fragments the user provides (e.g. `#pullrequestreview-<id>`).

Parse from the text:
- **Confidence score**: pattern like `Confidence Score: 4/5` or `4/5`.
- **Findings / unresolved comments**: count inline comments + "Findings" list items + the "Prompt to fix all with AI" section in the body comment.

#### C. Check exit conditions

Stop if ANY:
- Confidence is 5/5 AND zero unresolved comments.
- Max 5 iterations reached → report current state and remaining comments.

#### D. Fix actionable comments

For each unresolved Greptile comment:
1. Read the file(s) with Read. Understand the comment in context (use Grep/SearchCodebase to find related code).
2. Determine if actionable (code change needed) or informational/false positive.
3. If actionable, fix with Edit/Write. Keep changes minimal and focused.
4. If informational/false positive, note it but still resolve the thread in step E.

#### E. Resolve threads (GitHub)

Fetch unresolved review threads via GraphQL:
```
gh api graphql -f query='query($cursor: String) {
  repository(owner: "OWNER", name: "REPO") {
    pullRequest(number: PR_NUMBER) {
      reviewThreads(first: 100, after: $cursor) {
        pageInfo { hasNextPage endCursor }
        nodes { id isResolved comments(first: 1) { nodes { body path author { login } } } }
      }
    }
  }
}'
```
Resolve addressed threads:
```
gh api graphql -f query='mutation {
  t1: resolveReviewThread(input: {threadId: "ID1"}) { thread { isResolved } }
}'
```
Note: resolving requires `gh` with write scope. If only a token URL push is available, skip auto-resolve and ask the user to resolve in the GitHub UI; the next Greptile review will still reflect the code fix.

#### F. Commit and push

```
git add -A
git commit -m "address greptile review feedback (greploop iteration N)"
git push
```
Use token URL if `gh` auth is unavailable. Then go back to step A.

### 3. Report

Summarize:
```
Greploop complete (or stopped after N iterations).
Platform: GitHub
PR: #<number>
Iterations: N
Final confidence: X/5
Resolved: N comments
Remaining: N (if any)
```
If stopped early, list remaining comments with file:line and the comment text, plus suggested next steps.

## Notes for the TRAE environment

- TRAE runs in a non-interactive sandbox (CI=true, no TTY). All shell calls must be non-interactive; never use commands that prompt.
- Prefer the dedicated tools (Read/Edit/Write/Grep/Glob) over shell equivalents. Use RunCommand only for git/gh/curl/sleep.
- If `gh` is not authenticated and no token is provided, fall back to WebFetch for reading PR state, but pushing and comment-posting will require user-supplied credentials. In that case, tell the user what command to run and pause.
- Network egress goes through a proxy (HTTP_PROXY/HTTPS_PROXY). `curl` and git over HTTPS work; direct TCP may not.
- Max 5 iterations to avoid runaway loops. Always report state when exiting.
