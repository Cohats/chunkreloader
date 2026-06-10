# ChunkReloader

一个 Minecraft 1.21.1 NeoForge 模组，用于**区块重载(重新生成)**和**过期区块自动管理**。

## 功能

- **`/chunckreloader reload`** - 手动重载指定范围的区块
- **`/chunckreloader set`** - 游戏内直接修改配置，无需编辑文件
- **`/chunckreloader status`** - 查看当前配置和运行状态
- **自动重载** - 自动检测并重载超过指定天数未加载的区块
- **领地保护** - 支持 GriefDefender 保护区检测（软依赖）
- **配置文件** - 所有功能均可通过配置文件或命令调整

## 安装

1. 安装 **NeoForge 21.1+**（对应 Minecraft 1.21.1）
2. 将 `chunkreloader-1.0.0.jar` 放入 `.minecraft/mods/` 文件夹（客户端）或服务端 `mods/` 文件夹
3. 启动游戏/服务端

## 命令

所有命令需要 **OP 权限**（权限等级 2）。

### `/chunckreloader reload <x1> <z1> <x2> <z2> [force]`

重载指定矩形区域内的所有区块（区块坐标）。

| 参数 | 说明 |
|------|------|
| `x1`, `z1` | 第一个区块坐标 |
| `x2`, `z2` | 第二个区块坐标 |
| `force` | 可选，添加此参数忽略保护区域强制重载 |

**示例**:
```
/chunckreloader reload -10 -10 10 10
/chunckreloader reload 0 0 100 100 force
```

> **提示**: 命令使用的是**区块坐标**，不是方块坐标。区块坐标 = 方块坐标 ÷ 16（向下取整）。

### `/chunckreloader set <选项> <值>`

游戏内直接修改配置，立即生效。

| 选项 | 值类型 | 说明 |
|------|--------|------|
| `enableAutoReload` | `true` / `false` | 开关自动重载 |
| `staleDays` | 数字 | 区块过期天数 |
| `nonRecordArea` | `x1,z1,x2,z2` | 非记录区域（方块坐标） |
| `protectArea` | `x1,z1,x2,z2` | 保护区域（方块坐标），留空表示不使用 |
| `autoReloadInterval` | 数字 | 自动重载检查间隔（秒） |

**示例**:
```
/chunckreloader set enableAutoReload true
/chunckreloader set staleDays 15
/chunckreloader set nonRecordArea -1000,-1000,1000,1000
/chunckreloader set protectArea 100,100,200,200
/chunckreloader set autoReloadInterval 600
```

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
    # 是否开启自动重载
    enableAutoReload = false
    
    # 非记录区域（格式: x1,z1,x2,z2 方块坐标）
    # 在此区域内的区块不会被追踪加载时间
    nonRecordArea = "-50000,-50000,50000,50000"
    
    # 过期天数 - 超过此天数未加载的区块将被自动重载
    staleDays = 30
    
    # 保护区域（格式: x1,z1,x2,z2 方块坐标）
    # 在此区域内的区块不会被重载命令影响
    # 留空表示不使用
    protectArea = ""
    
    # 自动重载检查间隔（秒），0 = 每个游戏刻检查
    autoReloadInterval = 300
```

### 配置项说明

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `enableAutoReload` | `false` | 开启后自动检测并重载过期区块 |
| `nonRecordArea` | `-50000,-50000,50000,50000` | 默认世界出生点周围大范围不追踪，避免误重载玩家活动区域 |
| `staleDays` | `30` | 区块超过 30 天未被任何玩家加载，将被视为过期 |
| `protectArea` | `""` | 额外保护区域（例如你的建筑区域） |
| `autoReloadInterval` | `300` | 每 300 秒（5 分钟）检查一次过期区块 |

## 保护区

### GriefDefender 集成

如果服务端安装了 GriefDefender（领地插件），本模组会自动检测并跳过被领地保护的区块，防止误重载。

### 命令设置保护区

也可以通过 `/chunckreloader set protectArea x1,z1,x2,z2` 在游戏内设置。

## 兼容性

- **Minecraft 版本**: 1.21.1
- **NeoForge 版本**: 21.1+
- **服务端/客户端**: 两端通用（命令仅在服务端生效，需要 OP 权限）
- **GriefDefender**: 可选（软依赖），安装后自动启用领地保护检测

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
