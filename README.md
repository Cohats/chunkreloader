# ChunkReloader

一个 Minecraft 1.21.1 NeoForge 模组，用于**区块重载(重新生成)**和**过期区块自动管理**。

## 功能

- **`/chunckreloader reload`** - 手动重载指定世界的指定范围区块
- **`/chunckreloader set`** - 游戏内直接修改配置，无需编辑文件
- **`/chunckreloader get worldName`** - 获取所有可用世界名称列表
- **`/chunckreloader status`** - 查看当前配置和运行状态
- **自动重载** - 自动检测并重载超过指定天数未加载的区块
- **领地保护** - 支持 GriefDefender / FTB Chunks / OPAC（软依赖）
- **配置文件** - 所有功能均可通过配置文件或命令调整

## 安装

1. 安装 **NeoForge 21.1+**（对应 Minecraft 1.21.1）
2. 将 `chunkreloader-1.0.0.jar` 放入 `.minecraft/mods/` 文件夹（客户端）或服务端 `mods/` 文件夹
3. 启动游戏/服务端

## 命令

所有命令需要 **OP 权限**（权限等级 2）。

### `/chunckreloader get worldName`

获取所有可用世界名称列表，包含玩家数量。

**示例**:
```
/chunckreloader get worldName
```
输出:
```
=== Available Worlds ===
- minecraft:overworld (3 players)
- minecraft:the_nether (0 players)
- minecraft:the_end (0 players)
```

### `/chunckreloader reload <世界> <x1> <z1> <x2> <z2> [force]`

重载指定世界中矩形区域内的所有区块（区块坐标）。

| 参数 | 说明 |
|------|------|
| `世界` | 世界名称（如 `overworld`、`the_nether`、`the_end`），用 `/chunckreloader get worldName` 查看 |
| `x1`, `z1` | 第一个区块坐标 |
| `x2`, `z2` | 第二个区块坐标 |
| `force` | 可选，添加此参数忽略保护区域强制重载 |

**示例**:
```
/chunckreloader reload overworld -10 -10 10 10
/chunckreloader reload the_nether 0 0 50 50
/chunckreloader reload the_end 0 0 100 100 force
```

> **提示**: 如果世界名错误会提示 `Wrong world name`。区块坐标 = 方块坐标 ÷ 16（向下取整）。

### `/chunckreloader set <选项> <值>`

游戏内直接修改配置，立即生效。

| 选项 | 值类型 | 默认值 | 说明 |
|------|--------|--------|------|
| `enableAutoReload` | `<世界> true/false` | `overworld false` | 开关自动重载（按世界） |
| `staleDays` | `<世界> 天数` | `overworld 14` | 区块过期天数（按世界） |
| `nonRecordArea` | `<世界> <x1,z1,x2,z2>` | `overworld -50000,-50000,50000,50000` | 非记录区域（含世界名） |
| `protectArea` | `<世界> <x1,z1,x2,z2>` | `overworld -50000,-50000,50000,50000` | 保护区域（含世界名） |
| `autoReloadInterval` | 数字 | `3600` | 自动重载检查间隔（秒） |

**示例**:
```
/chunckreloader set enableAutoReload overworld true
/chunckreloader set staleDays overworld 14
/chunckreloader set staleDays the_end 30
/chunckreloader set nonRecordArea overworld -1000,-1000,1000,1000
/chunckreloader set protectArea the_end 0,0,500,500
/chunckreloader set autoReloadInterval 600
```

> **注意**: `enableAutoReload` 和 `staleDays` 现在也按世界配置，需要指定世界名。世界名错误会提示 `Wrong world name`。

### `/chunckreloader status`

显示当前所有配置值和运行状态。

**示例**:
```
/chunckreloader status
```

### 工作原理

1. 清除指定区块在 `.mca` 区域文件中的记录
2. 从内存中卸载该区块
3. 当玩家下次靠近时，游戏会自动重新生成地形

## 配置文件

路径: `config/chunkreloader-common.toml`

也可以通过 `/chunckreloader set` 命令在游戏内修改，无需编辑文件。

```toml
[general]
    # 是否开启自动重载 (格式: "world:true" 或 "world:false")
    enableAutoReload = "overworld:false"
    
    # 非记录区域（格式: "world:x1,z1,x2,z2" 区块坐标）
    # 在此区域内的区块不会被追踪加载时间
    nonRecordArea = "overworld:-50000,-50000,50000,50000"
    
    # 过期天数 (格式: "world:天数")
    staleDays = "overworld:14"
    
    # 保护区域（格式: "world:x1,z1,x2,z2" 区块坐标）
    # 默认与非记录区域相同
    protectArea = "overworld:-50000,-50000,50000,50000"
    
    # 自动重载检查间隔（秒），0 = 每个游戏刻检查
    autoReloadInterval = 3600
```

### 配置项说明

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `enableAutoReload` | `overworld:false` | 开启后自动检测并重载过期区块（按世界） |
| `nonRecordArea` | `overworld:-50000,-50000,50000,50000` | 默认主世界出生点周围大范围不追踪，避免误重载玩家活动区域 |
| `staleDays` | `overworld:14` | 区块超过 14 天未加载将被重载（按世界） |
| `protectArea` | `overworld:-50000,-50000,50000,50000` | 保护区域（与非记录区域默认相同） |
| `autoReloadInterval` | `3600` | 每 3600 秒（1 小时）检查一次过期区块 |

## 保护区

本模组自动支持以下领地/保护系统，安装后即可自动检测并跳过被保护的区块，防止误重载：

| 保护系统 | 类型 | 说明 |
|----------|------|------|
| **GriefDefender** | 软依赖 | 安装后自动识别领地 |
| **FTB Chunks** | 软依赖 | 安装后自动识别已认领区块 |
| **Open Parties and Claims** (开放领地) | 软依赖 | 安装后自动识别声明区块 |
| **配置文件保护区域** | 内置 | 通过 `protectArea` 配置项手动指定 |

所有保护系统均为**软依赖**——装了自动生效，没装不影响模组运行。

### 命令设置保护区

也可以通过 `/chunckreloader set protectArea <世界> <x1,z1,x2,z2>` 在游戏内设置。
世界名错误会提示 `Wrong world name`。

## 兼容性

- **Minecraft 版本**: 1.21.1
- **NeoForge 版本**: 21.1+
- **服务端/客户端**: 两端通用（命令仅在服务端生效，需要 OP 权限）
- **保护系统**: GriefDefender / FTB Chunks / Open Parties and Claims（均为软依赖）

## 自动重载机制

1. 玩家加载区块时，模组记录该区块的加载时间
2. 每隔 `autoReloadInterval` 秒，检查所有记录的区块
3. 如果某个区块的最后加载时间超过了 `staleDays` 天，将其加入重载队列
4. 每次最多处理 50 个过期区块，避免造成服务器卡顿
5. 重载后从追踪列表中移除该区块，除非再次被玩家加载

## 构建

```bash
./gradlew build
```

构建产物位于 `build/libs/chunkreloader-1.0.0.jar`
