# Requirements Document

## Introduction

在搜索页工具栏点击「执行脚本」后，用户可以编写并运行 Lua 脚本，对当前已绑定进程执行内存读写、搜索结果遍历等自动化操作。第一版覆盖：脚本编辑、运行、停止、输出展示、脚本持久化、`gg.*` 兼容读写 API。ImGui 脚本界面、`gg.searchNumber` / `gg.refineNumber` 不在本版本范围内。

## Glossary

- **System**: Mamu 悬浮窗搜索页及其脚本子系统
- **User**: 已获得 Root 权限并启动悬浮窗的操作者
- **Bound Process**: 当前已通过设置页绑定的目标进程
- **Script**: 一段 Lua 5.2 兼容源码
- **Script Host**: 在独立线程中执行 Script 的 Luaj 运行时
- **Script API**: 以 `gg` 全局表暴露给 Script 的函数集合
- **Script Output**: 脚本通过 `print` 产生的文本
- **Saved Script**: 持久化在应用私有目录 `scripts/` 中的 `.lua` 文件
- **Type Flag**: 与 GameGuardian 对齐的数值类型常量，例如 `gg.TYPE_DWORD`

## Requirements

### Requirement 1: 打开脚本对话框

**User Story:** AS User, I want 点击「执行脚本」打开编辑器, so that 我能编写或选择脚本

#### Acceptance Criteria

1. WHEN User 点击搜索页工具栏「执行脚本」, THE System SHALL 显示脚本对话框
2. WHILE 脚本对话框可见, THE System SHALL 展示脚本编辑区、输出区、运行按钮、停止按钮、保存按钮、载入按钮、关闭按钮
3. IF 当前未绑定进程, THE System SHALL 仍打开脚本对话框，并在输出区显示「未绑定进程」提示

### Requirement 2: 编辑与运行脚本

**User Story:** AS User, I want 输入 Lua 并立即运行, so that 我能自动化内存操作

#### Acceptance Criteria

1. WHEN User 点击运行按钮且编辑区包含非空文本, THE System SHALL 在后台线程启动 Script Host 执行该文本
2. WHILE Script Host 正在运行, THE System SHALL 将运行按钮置为不可用，并将停止按钮置为可用
3. IF 编辑区文本为空, THE System SHALL 拒绝启动执行，并在输出区显示「脚本为空」
4. WHEN Script 正常结束, THE System SHALL 在输出区追加「执行完成」并恢复运行按钮可用

### Requirement 3: 停止脚本

**User Story:** AS User, I want 中途停止脚本, so that 长时间循环不会卡住界面

#### Acceptance Criteria

1. WHEN User 点击停止按钮且 Script Host 正在运行, THE System SHALL 在 2 秒内请求 Script Host 中断
2. WHEN Script Host 因停止请求退出, THE System SHALL 在输出区追加「已停止」并恢复运行按钮可用
3. IF Script Host 未在运行, THE System SHALL 保持停止按钮不可用

### Requirement 4: 脚本 API

**User Story:** AS User, I want 在脚本里用 `gg.*` 读写已绑定进程内存, so that 我能批量改值

#### Acceptance Criteria

1. WHEN Script 调用 `gg.getTargetInfo()`, THE System SHALL 返回包含 `pid` 与 `processName` 的表；未绑定时返回 `nil`
2. WHEN Script 调用 `gg.readValue(address, type)` 且进程已绑定, THE System SHALL 按 Type Flag 读取该地址并返回数值或字符串
3. WHEN Script 调用 `gg.writeValue(address, value, type)` 且进程已绑定, THE System SHALL 按 Type Flag 写入该地址并返回 `true` 或 `false`
4. WHEN Script 调用 `gg.toast(message)`, THE System SHALL 通过现有 NotificationOverlay 显示该文本
5. WHEN Script 调用 `print(...)`, THE System SHALL 将文本追加到输出区
6. IF Script 在未绑定进程时调用内存读写 API, THE System SHALL 向 Script 返回错误结果，并保持进程状态不变
7. WHEN Script 调用 `gg.getResults(maxCount)`, THE System SHALL 返回当前搜索结果中不超过 `maxCount` 条的表，每项含 `address`、`value`、`flags`
8. WHEN Script 调用 `gg.getSelectedResults()`, THE System SHALL 返回搜索页当前勾选结果的表，每项含 `address`、`value`、`flags`
9. WHILE Script 运行, THE System SHALL 在 `gg` 表中提供 `TYPE_BYTE`、`TYPE_WORD`、`TYPE_DWORD`、`TYPE_QWORD`、`TYPE_FLOAT`、`TYPE_DOUBLE` 常量，取值与 GameGuardian 一致

### Requirement 5: 错误与超时

**User Story:** AS User, I want 看到脚本错误, so that 我能修正脚本

#### Acceptance Criteria

1. IF Script 语法错误或运行时错误, THE System SHALL 停止执行，并在输出区显示错误类型与行号
2. IF Script 连续运行超过 60 秒且 User 未点击停止, THE System SHALL 请求中断 Script Host，并在输出区显示「执行超时」
3. IF Script 调用未声明的 API, THE System SHALL 将调用视为运行时错误并停止该 Script

### Requirement 6: 脚本持久化

**User Story:** AS User, I want 保存和载入脚本, so that 常用脚本不用每次重写

#### Acceptance Criteria

1. WHEN User 点击保存且编辑区非空, THE System SHALL 将当前文本写入应用私有目录 `scripts/` 下的 `.lua` 文件
2. WHEN User 点击载入, THE System SHALL 列出 `scripts/` 中的 `.lua` 文件供选择
3. WHEN User 选择一个 Saved Script, THE System SHALL 用该文件内容替换编辑区文本
4. WHILE 应用重启后, THE System SHALL 在再次打开脚本对话框时恢复上次编辑区文本

### Requirement 7: 安全边界

**User Story:** AS User, I want 脚本只能操作目标进程内存, so that 设备其他部分不受影响

#### Acceptance Criteria

1. WHILE Script Host 运行, THE System SHALL 仅向 Script 暴露 Script API 中声明的函数与 Lua 标准库中的 `base`、`table`、`string`、`math`、`bit32`
2. IF Script 尝试加载 Java 类、执行操作系统命令、访问文件系统, THE System SHALL 拒绝该操作并记为运行时错误
3. WHEN 脚本对话框关闭, THE System SHALL 停止仍在运行的 Script Host
