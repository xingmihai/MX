# Requirements Document

## Introduction

在搜索页工具栏点击「执行脚本」后，用户从本地目录选择 `.lua` 文件运行，或输入 http(s) URL 下载后运行。脚本对当前已绑定进程执行内存读写、搜索结果遍历等自动化操作。第一版覆盖：本地目录浏览、URL 运行、停止、输出展示、`gg.*` 兼容读写 API。ImGui 脚本界面、`gg.searchNumber` / `gg.refineNumber` 不在本版本范围内。

## Glossary

- **System**: Mamu 悬浮窗搜索页及其脚本子系统
- **User**: 已获得 Root 权限并启动悬浮窗的操作者
- **Bound Process**: 当前已通过设置页绑定的目标进程
- **Script**: 一段 Lua 5.2 兼容源码
- **Script Host**: 在独立线程中执行 Script 的 Luaj 运行时
- **Script API**: 以 `gg` 全局表暴露给 Script 的函数集合
- **Script Output**: 脚本通过 `print` 产生的文本
- **Local Script**: 设备存储中的 `.lua` 文件
- **Remote Script URL**: 以 `http` 或 `https` 开头的脚本下载地址
- **Type Flag**: 与 GameGuardian 对齐的数值类型常量，例如 `gg.TYPE_DWORD`

## Requirements

### Requirement 1: 打开脚本对话框

**User Story:** AS User, I want 点击「执行脚本」打开选择界面, so that 我能从本地或 URL 运行脚本

#### Acceptance Criteria

1. WHEN User 点击搜索页工具栏「执行脚本」, THE System SHALL 显示脚本对话框
2. WHILE 脚本对话框可见, THE System SHALL 展示当前路径、目录条目列表、URL 输入框、运行链接按钮、停止按钮、关闭按钮、输出区
3. IF 当前未绑定进程, THE System SHALL 仍打开脚本对话框，并在输出区显示「未绑定进程」提示

### Requirement 2: 本地目录浏览与运行

**User Story:** AS User, I want 看到当前路径并自己选择文件, so that 我能运行本地 Lua 脚本

#### Acceptance Criteria

1. WHEN 脚本对话框打开, THE System SHALL 列出当前目录下的子目录与 `.lua` 文件
2. WHEN User 点击子目录, THE System SHALL 进入该目录并刷新列表与路径显示
3. WHEN User 点击上级目录按钮, THE System SHALL 显示父目录内容
4. WHEN User 点击 `.lua` 文件, THE System SHALL 读取该文件并在后台线程启动 Script Host
5. IF 当前目录没有子目录且没有 `.lua` 文件, THE System SHALL 显示空目录提示
6. WHEN Script 正常结束, THE System SHALL 在输出区追加「执行完成」并恢复运行链接按钮可用

### Requirement 3: 停止脚本

**User Story:** AS User, I want 中途停止脚本, so that 长时间循环不会卡住界面

#### Acceptance Criteria

1. WHEN User 点击停止按钮且 Script Host 正在运行, THE System SHALL 在 2 秒内请求 Script Host 中断
2. WHEN Script Host 因停止请求退出, THE System SHALL 在输出区追加「已停止」并恢复运行链接按钮可用
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
10. WHEN Script 调用 `gg.makeRequest(url)`, THE System SHALL 返回含 `code`、`url`、`content`、`error` 的表

### Requirement 5: 错误与超时

**User Story:** AS User, I want 看到脚本错误, so that 我能修正脚本

#### Acceptance Criteria

1. IF Script 语法错误或运行时错误, THE System SHALL 停止执行，并在输出区显示错误类型与行号
2. IF Script 连续运行超过 60 秒且 User 未点击停止, THE System SHALL 请求中断 Script Host，并在输出区显示「执行超时」
3. IF Script 调用未声明的 API, THE System SHALL 将调用视为运行时错误并停止该 Script

### Requirement 6: URL 运行脚本

**User Story:** AS User, I want 输入链接运行脚本, so that 我不用把文件拷到手机

#### Acceptance Criteria

1. WHEN User 点击运行链接且 URL 以 `http` 或 `https` 开头, THE System SHALL 下载该地址内容并启动 Script Host
2. IF URL 为空或协议不是 `http`/`https`, THE System SHALL 拒绝下载，并在输出区显示原因
3. IF 下载失败、内容为空或超过 1MB, THE System SHALL 拒绝执行，并在输出区显示原因
4. WHILE 应用再次打开脚本对话框, THE System SHALL 恢复上次浏览的本地目录路径

### Requirement 7: 安全边界

**User Story:** AS User, I want 脚本只能操作目标进程内存, so that 设备其他部分不受影响

#### Acceptance Criteria

1. WHILE Script Host 运行, THE System SHALL 仅向 Script 暴露 Script API 中声明的函数与 Lua 标准库中的 `base`、`table`、`string`、`math`、`bit32`
2. IF Script 尝试加载 Java 类、执行操作系统命令、访问文件系统, THE System SHALL 拒绝该操作并记为运行时错误
3. WHEN 脚本对话框关闭, THE System SHALL 停止仍在运行的 Script Host
