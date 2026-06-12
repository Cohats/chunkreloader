# ChunkReloader

一个 Minecraft 1.21.1 NeoForge 模组，用于**区块重载(重新生成)**和**过期区块自动管理**。

## 功能

- **`/chunckreloader reload`** - 手动重载指定世界的指定范围区块
- **`/chunckreloader reload <世界> all`** - 重载所有玩家更新过的过期区块
- **`/chunckreloader first <世界>`** - 强制删除保护区以外的所有区块（工厂重置）
- **`/chunckreloader set`** - 游戏内直接修改配置，无需编辑文件
- **`/chunckreloader get worldName`** - 获取所有可用世界名称列表
- **`/chunckreloader status`** - 查看当前配置和运行状态（按世界显示）
- **自动重载** - 自动检测并重载超过指定天数未加载的区块
- **领地保护** - 支持 OPAC 开放领地（软依赖）
- **防卡顿分批处理** - 所有重载操作每 tick 最多处理 50 个区块，避免服务器卡顿
- **玩家更新检测** - 矩形重载默认只重载被玩家访问/更新过的区块，避免无效重载
- **Tab 补全** - 世界名称参数支持 Tab 自动补全
- **配置文件** - 所有功能均可通过配置文件或命令调整

## 安装

1. 安装 **NeoForge 21.1+**（对应 Minecraft 1.21.1）
2. 将 `chunkreloader-2.0.3.jar` 放入 `.minecraft/mods/` 文件夹（客户端）或服务端 `mods/` 文件夹
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

重载指定世界中矩形区域内的所有区块（输入方块坐标，自动转换为区块坐标）。

| 参数 | 说明 |
|------|------|
| `世界` | 世界名称（如 `overworld`、`the_nether`、`the_end`），用 `/chunckreloader get worldName` 查看 |
| `x1`, `z1` | 第一个点的方块坐标 |
| `x2`, `z2` | 第二个点的方块坐标 |
| `force` | 可选，添加此参数忽略保护区域和玩家更新检测，强制重载所有区块 |

**默认行为（不加 force）**:
- 跳过保护区域内的区块
- **只重载被玩家访问/更新过的区块**（通过 ChunkLoadTracker 追踪），未追踪的区块跳过
- 分批处理（每 tick 50 个），避免服务器卡顿

**使用 force**:
- 忽略保护区域
- 重载矩形内所有区块，无论是否被玩家更新过

**示例**:
```
/chunckreloader reload overworld -160 -160 160 160
/chunckreloader reload the_nether 0 0 800 800
/chunckreloader reload the_end 0 0 1600 1600 force
```

> **提示**: 输入的是**方块坐标**（F3 调试界面上的坐标），模组会自动除 16 向下取整转成区块坐标。如果世界名错误会提示 `Wrong world name`。

### `/chunckreloader reload <世界> all [force]`

重载指定世界中所有被玩家访问/更新过的区块，跳过保护区域。

| 参数 | 说明 |
|------|------|
| `世界` | 世界名称（如 `overworld`、`the_nether`、`the_end`） |
| `force` | 可选，忽略保护区域强制重载 |

**注意**: 只有被玩家加载过的区块才会被追踪。首次运行`all`时如果没有追踪数据会提示"no tracked chunks"。

**示例**:
```
/chunckreloader reload overworld all
/chunckreloader reload the_end all force
```

### `/chunckreloader set <选项> <值>`

游戏内直接修改配置，立即生效。

| 选项 | 值类型 | 默认值 | 说明 |
|------|--------|--------|------|
| `enableAutoReload` | `<世界> true/false` | `overworld false` | 开关自动重载（按世界） |
| `staleDays` | `<世界> 天数` | `overworld 14` | 区块过期天数（按世界） |
| `nonRecordArea` | `<世界> <x1,z1,x2,z2>` | `overworld -50000,-50000,50000,50000` | 非记录区域（按世界，方块坐标） |
| `protectArea` | `<世界> <x1,z1,x2,z2>` | `overworld -50000,-50000,50000,50000` | 保护区域（按世界，方块坐标） |
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

> **注意**: 所有按世界配置的选项都需要指定世界名。世界名错误会提示 `Wrong world name`。

### `/chunckreloader first <世界>`

**危险操作** — 扫描指定世界所有 `.mca` 区域文件，强制删除保护区以外的**所有区块**，下次加载时重新生成。

| 参数 | 说明 |
|------|------|
| `世界` | 世界名称（如 `overworld`、`the_nether`、`the_end`），支持 Tab 补全 |

**工作原理**:
1. 扫描世界目录下所有 `r.*.mca` 区域文件
2. 解析每个文件的头部信息，找出所有已存在的区块
3. 跳过保护区域内的区块（OPAC / 配置 protectArea）
4. 将未保护的区块排队清除（每 tick 50 个）
5. 玩家下次靠近时，游戏自动重新生成地形

> **注意**: 此命令不会跳过玩家更新检测——它会删除所有能找到的未保护区区块。
> 如果要删除大量区块，请确保服务器配置了适当的自动重载间隔，避免瞬间加载所有区块导致卡顿。

**示例**:
```
/chunckreloader first overworld
/chunckreloader first the_nether
/chunckreloader first the_end
```

### `/chunckreloader status`

显示当前所有世界的配置值和运行状态。如果正在进行批量重载，还会显示实时进度。

**示例**:
```
/chunckreloader status
```
输出示例:
```
=== ChunkReloader Status ===
overworld:
  Auto Reload: false
  Stale Days: 14d
  Non-Record Area: -50000,-50000,50000,50000
  Protect Area: -50000,-50000,50000,50000
the_nether:
  Auto Reload: false
  Stale Days: 14d
  Non-Record Area: -50000,-50000,50000,50000
  Protect Area: -50000,-50000,50000,50000
the_end:
  Auto Reload: false
  Stale Days: 14d
  Non-Record Area: -50000,-50000,50000,50000
  Protect Area: -50000,-50000,50000,50000
Check Interval: 3600s
```
有重载进行中时额外显示:
```
=== Reload Progress ===
Progress: 45%
Processed: 4500/10000
Regenerated: 3800
Skipped: 600
Failed: 100
```

### 工作原理

1. 玩家加载区块时，模组自动追踪该区块的加载信息（用于 `all` 命令和自动重载）
2. 通过 Minecraft 的 `ChunkStorage.write(ChunkPos, CompoundTag)` API 将区块数据写入空的 NBT 并标记状态为 `minecraft:empty`（同时更新文件磁盘和内存缓存，确保区块真正被清除）
3. 从内存中卸载该区块并阻止旧数据回写
4. 当玩家下次靠近时，游戏检测到区块状态为 empty，自动重新生成地形

> **技术说明**: 旧版本使用 RandomAccessFile 直接修改 MCA 文件头部，但 Minecraft 的 RegionFile 会缓存头部信息在内存中，导致磁盘修改不生效。v1.1.0+ 改用 ChunkStorage API（ChunkMap extends ChunkStorage），该 API 会正确同步更新磁盘文件和内存缓存。
> v2.0.0 还修复了动物堆积问题——使用 `EntityPersistentStorage.storeEntities()` API 正确清除实体数据（同理，直接修改实体 MCA 文件也有 RegionFile 缓存问题）。

### 防卡顿机制

所有重载操作（包括手动命令和自动重载）均采用**分批处理**：
- 每 tick 最多处理 50 个区块
- 大范围重载会在后台逐步完成，不会导致服务器"时间静止"
- 每处理完 25% 会向所有 OP 玩家发送进度提示

**进度提示示例**:
```
[ChunkReloader] ■■■■■■■□□□ 50% (5000/10000 ↑4200 ↓700 ✗100)
```
```
[ChunkReloader] Reload complete! 4200 regenerated, 700 skipped, 100 failed (10000 chunks, 45s)
```

也可以使用 `/chunckreloader status` 查看更详细的实时进度。

### 玩家更新检测

矩形重载命令 `/chunckreloader reload <世界> <x1> <z1> <x2> <z2>`（不加 force）：
- **只重载被玩家访问过的区块**，未追踪的区块自动跳过
- 有效避免重载大范围无人区时浪费性能
- 添加 `force` 参数可忽略此检测，强制重载所有区块

## 配置文件

路径: `config/chunkreloader-common.toml`

也可以通过 `/chunckreloader set` 命令在游戏内修改，无需编辑文件。

```toml
[general]
    # 是否开启自动重载 (格式: "world:true" 或 "world:false")
    enableAutoReload = "overworld:false"
    
    # 非记录区域（格式: "world:x1,z1,x2,z2" 方块坐标）
    # 在此区域内的区块不会被追踪加载时间
    nonRecordArea = "overworld:-50000,-50000,50000,50000"
    
    # 过期天数 (格式: "world:天数")
    staleDays = "overworld:14"
    
    # 保护区域（格式: "world:x1,z1,x2,z2" 方块坐标）
    # 默认与非记录区域相同
    protectArea = "overworld:-50000,-50000,50000,50000"
    
    # 自动重载检查间隔（秒），0 = 每个游戏刻检查
    autoReloadInterval = 3600
```

### 配置项说明

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `enableAutoReload` | `overworld:false` | 开启后自动检测并重载过期区块（按世界） |
| `nonRecordArea` | `overworld:-50000,-50000,50000,50000` | 方块坐标，默认主世界出生点周围约 3125x3125 区块不追踪，避免误重载 |
| `staleDays` | `overworld:14` | 区块超过 14 天未加载将被重载（按世界） |
| `protectArea` | `overworld:-50000,-50000,50000,50000` | 保护区域（与非记录区域默认相同） |
| `autoReloadInterval` | `3600` | 每 3600 秒（1 小时）检查一次过期区块 |

## 保护区

本模组自动支持以下领地/保护系统，安装后即可自动检测并跳过被保护的区块，防止误重载：

| 保护系统 | 类型 | 说明 |
|----------|------|------|
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
- **保护系统**: Open Parties and Claims（软依赖）

## 自动重载机制

1. 玩家加载区块时，模组自动记录该区块（只要不在非记录区域和保护区域内）
2. 每隔 `autoReloadInterval` 秒，检查所有记录的区块（仅在对应世界开启了自动重载时）
3. 如果某个区块的最后加载时间超过了 `staleDays` 天，将其加入重载队列
4. 每次最多处理 50 个过期区块，避免造成服务器卡顿
5. 重载后从追踪列表中移除该区块，除非再次被玩家加载

> **注意**: 区块追踪始终启用，但自动重载检查仅在 `enableAutoReload` 开启的世界中执行。手动使用 `all` 命令时无需开启自动重载。

## 构建

```bash
./gradlew build
```

构建产物位于 `build/libs/chunkreloader-2.0.3.jar`
