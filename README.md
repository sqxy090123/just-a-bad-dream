# Just A Bad Dream (JABD) — 只是一场噩梦

> Minecraft Forge 模组，支持 1.12.2 / 1.16.5 / 1.18.2 / 1.19.2 / 1.20.1
> 仓库默认包含 **1.20.1 完整实现**；旧版本移植指南见 `VERSION_ADAPTATION.java`。

---

## 1. 功能一览

| 功能 | 说明 |
|------|------|
| **温暖的床（Warm Bed）** | 在原版床的合成表中，把 3 块羊毛中的任意 **1 块换成下界之星**，即可合成。<br>合成支持 3 种位置（左 / 中 / 右被替换）。 |
| **梦境现实叠加态** | 成功睡过温暖的床 → 进入该状态。默认持续到"下一次睡眠"，或直到真死触发回档自动清除。 |
| **后台存档备份** | 进入叠加态时**异步**对整个 `world/` 目录做全量备份（可配置 ZIP 压缩或裸拷贝）。<br>按玩家 UUID 分目录，每玩家默认保留最近 3 份。 |
| **真正死亡判定** | 处于叠加态且真正死亡 → 回档。<br>是否"真死"可配置：是否检查主/副手的不死图腾（默认启用，检查主副手任一）。 |
| **回档恢复** | 保存世界 → 关闭存档 IO → 用备份覆盖 `world/` → 踢出所有玩家并提示"重新进入服务器"（SOFT 模式，默认）或直接关机（HARD 模式）。 |
| **可配置** | `config/jabbadream-common.toml` + `saves/<world>/serverconfig/jabbadream-server.toml`。 |
| **命令控制** | `/baddream`（或别名 `/jabd`），支持状态查询、叠加态清除、热修改触发/图腾配置、手动回档、列出备份等。 |

---

## 2. 合成配方（任意一种即可）

```
 ┌──┬──┬──┐
 │W │W │W │ ← 羊毛行
 ├──┼──┼──┤
 │N │W │# │ ← 羊毛行（左/中/右任一位置换成 下界之星[N]）
 ├──┼──┼──┤
 │P │P │P │ ← 任意木板
 └──┴──┴──┘
 → 产出：1 × 温暖的床
```
*（W = 任意羊毛 / P = 任意木板 / N = 下界之星）*

---

## 3. 命令速查 `/baddream …`（别名 `/jabd`）

| 命令 | 权限 | 说明 |
|------|------|------|
| `/baddream status` | 0 | 查看自己当前叠加态、醒来 tick、备份 ID |
| `/baddream status <玩家>` | 2 | 管理员查看指定玩家 |
| `/baddream clear [玩家]` | 0→自己 2→他人 | 清除叠加态（不回档） |
| `/baddream enter [玩家]` | 2 | **测试用** 强行进入叠加态（不做备份！） |
| `/baddream config trigger <WAKE_UP\|LAY\|RIGHT_CLICK>` | 2 | 热修改床触发叠加态的方式 |
| `/baddream config totem <true\|false>` | 2 | 热修改"真死是否判定不死图腾" |
| `/baddream config slot <MAIN_HAND\|OFF_HAND\|BOTH_HANDS>` | 2 | 热修改图腾检查槽位 |
| `/baddream restore <备份ID>` | 3 | 管理员手动回档到指定备份 |
| `/baddream list [玩家]` | 0→自己 2→他人 | 列出可用备份 ID |
| `/baddream reload` | 3 | 清除命令运行时覆盖，恢复配置文件值 |
| `/baddream help` | 0 | 帮助 |

> 💡 所有 `config` 子命令都是**运行时覆盖**，服务器重启或执行 `/baddream reload` 后恢复为配置文件值。

---

## 4. 配置文件速查

### 4.1 通用配置 `config/jabbadream-common.toml`（客户端 + 服务端）

```toml
[trigger]
    # WAKE_UP  = 成功睡醒后进入（默认，推荐）
    # LAY      = 躺下时立刻进入
    # RIGHT_CLICK = 右键床时立刻进入
    bedTriggerMode = "WAKE_UP"

[real_death]
    # true = 不死图腾能救命（不触发回档）；false = 无视图腾
    checkTotemOnRealDeath = true
    # MAIN_HAND / OFF_HAND / BOTH_HANDS (默认)
    checkTotemSlots = "BOTH_HANDS"

[messages]
    rollbackBroadcast = true
    statusDisplayName = "梦境现实叠加态"
```

### 4.2 每世界存档配置 `saves/<world>/serverconfig/jabbadream-server.toml`

```toml
[backup]
    backupDirectory = "jabbadream_backups"   # 相对 world/ 的路径，也可写绝对路径
    maxBackupPerPlayer = 3                   # 每玩家最多保留备份数（0 = 无限）
    backupCompression = true                 # true=ZIP（省空间）false=直接复制（更快）
    backupOnDedicatedOnly = false            # true=仅专用服启用备份（省单人性能）
    rollbackDelayTicks = 2                   # 死亡后等待多少 tick 再回档

[bed_behavior]
    warmBedExplodeInNether = true            # true=在下界/末地像原版床那样爆炸
```

---

## 5. 构建指南

### 5.1 环境要求

| JABD 子项目 | 最低 Java | Forge 版本线 | 推荐 Gradle |
|-------------|-----------|--------------|-------------|
| **1.20.1** （默认）| Java 17 | Forge 47.x | Gradle 8.3+ |
| 1.19.4 | Java 17 | Forge 45.x | Gradle 8.x |
| 1.18.2 | Java 17 | Forge 40.x | Gradle 7.5+ |
| 1.16.5 | Java 8  | Forge 36.x | Gradle 6.9.x |
| 1.12.2 | Java 8  | Forge 14.x | Gradle 4.10.3 |

### 5.2 一键构建（本地）

```bash
# 1) 确保本机 Java 17
java -version   # openjdk version "17.x"

# 2) 进入默认子项目 1.20.1，生成 wrapper（首次）
cd 1.20.1
gradle wrapper --gradle-version 8.5

# 3) 构建（首次会自动下载 Forge + MC mappings，需要几分钟）
./gradlew build    # Windows: gradlew.bat build

# 4) 产物位置
ls build/libs/
# → jabbadream-1.0.0.jar        （需进一步 reobf；运行 jar task 后会自动 reobf）
```

对于要启用其它旧版本子项目：编辑根 `settings.gradle` 取消对应的 `include` 行，并参考 `VERSION_ADAPTATION.java` 按说明移植代码。

### 5.3 安装

把最终 `build/libs/jabbadream-*.jar` 扔进 `.minecraft/mods/` 目录即可。
**专用服 / 单人都可运行；必须 Forge 环境（非 Fabric）。**

---

## 6. 时序与原理（想了解实现的用户）

```
玩家右键温暖的床
    │
    ▼  触发模式 = WAKE_UP / LAY / RIGHT_CLICK（默认 WAKE_UP）
成功睡醒 → requestBackup(ServerPlayer)
    │
    ├─ 后台线程池执行 BackupRollbackHandler#doBackup
    │     ├─ saveAllNow() 确保点-in-time 快照
    │     ├─ 复制 world/* → world/jabbadream_backups/players/<uuid>/<ts>_xxxx/
    │     └─ ZIP or copy，超出上限删除最旧备份
    │
    ▼ 备份完成（主线程回到 Capability 写入）
enterDreamState(wakeUpTime, backupId)
    → inDreamState = true，叠加态持续到下一次睡眠 / 清除 / 真死回档
    │
    │ 玩家自由活动期间……
    │
    ▼ LivingDeathEvent(LOWEST, receiveCanceled=true)
 玩家死亡？
    ├─ inDreamState == false → 忽略（原版死亡流程）
    ├─ 真死判定 = checkTotem(totemCheckSlot)
    │   └─ 有图腾 → 叠加态保留，玩家被救，不回档
    └─ 真正死亡
        ├─ event.setCanceled(true) 阻止掉装备/死亡信息
        ├─ 加入回档调度队列（延迟 rollbackDelayTicks tick）
        └─ 服务器 tick 到达时：
               saveAllNow() → closeStorage() → 覆盖 world/ → 踢出玩家 SOFT/HALT
```

---

## 7. 常见问题 FAQ

- **Q：回档后我（或其他玩家）为什么被踢出服务器？**
  A：这是设计行为（SOFT 策略）。重新进入即可看到还原后的世界。如希望自动重启请改为 HARD 策略（同时给启动脚本加 auto-restart 循环）。

- **Q：我的图腾触发了但还是回档了？**
  A：检查 `/baddream config totem` 是否被热改为 `false`，或配置文件 `checkTotemOnRealDeath=false`。
  其他模组自定义的图腾物品需要走 `ForgeHooks.onLivingUseTotem` 才会被识别（默认已识别）。

- **Q：叠加态中我想"取消今晚的噩梦"怎么办？**
  A：`/baddream clear` 清除叠加态即可。死亡不会再回档（叠加态已经退出）。

- **Q：备份文件在哪？可以手动删吗？**
  A：`world/jabbadream_backups/players/<uuid>/<timestamp>_xxxx[.zip]`，可以直接删。
  每玩家默认保留 3 份，最旧的自动清理。

---

## 8. 移植到其它 Forge 版本

请打开仓库根目录的 [`VERSION_ADAPTATION.java`](./VERSION_ADAPTATION.java)，包含从 1.12.2 到 1.20.x 每一次关键 API 变迁的 patch 级说明（注册、Capability、命令、配置、睡眠/死亡事件、存档访问、资源格式）。

---

**祝你好梦……即便只是噩梦一场。🌙**
