/* ==========================================================
 * Just A Bad Dream (JABD) — Cross-Version Adaptation Guide
 * 支持版本：1.12.2 / 1.16.5 / 1.18.2 / 1.19.2 / 1.20.x（Forge）
 *
 * 默认实现版本：1.20.1。下文列出每向旧版本移植时"必须变更"的点。
 * 在 settings.gradle 中取消对应 include 后，将本文件对应 patch 应用到子项目即可。
 * ========================================================== */

/* ======================================================
 * §0 构建脚本 (build.gradle)
 * ======================================================
 *
 * 1.20.x   → FG 5.1+, mappings channel: 'official', version: '1.20.x'
 *            Java 17 ; modLoader = "javafml" 47+
 *
 * 1.19.4   → mappings channel: 'official', version: '1.19.4'
 *            Java 17 ; loader 45+ ; CreativeModeTab 仍用 DeferredRegister
 *
 * 1.19.2   → 同上（loader 43+）。命令 API 仍 Brigadier；
 *            注意 1.19.2 有 PlayerEvent.PlayerWakeUpEvent，名字同 1.20.1。
 *
 * 1.18.2   → mappings channel: 'official', version: '1.18.2'
 *            Java 17 ; loader 40+ 。CreativeModeTab 不再用独立注册，
 *            直接在 items 上用 setCreativeTab(CreativeModeTab.TAB_DECORATIONS)。
 *            JABDCreativeModeTabs 整个类删除，注册 BlockItem 时加：
 *                  new Item.Properties().tab(CreativeModeTab.TAB_DECORATIONS)
 *
 * 1.16.5   → mappings channel: 'official', version: '1.16.5'
 *            Java 8-16；但 FG 要求 Java 8。
 *            ForgeGradle 4.1+；resource 里写 META-INF/mods.toml (loader v36+)
 *            DeferredRegister OK，但 Registrar<?> 写法略有差异（返回 RegistryObject 不需要 cast）。
 *            无 CreativeModeTab 独立注册：同上 Item.Properties.tab(...)
 *
 * 1.12.2   → 无 DeferredRegister，全走 GameRegistry.register。
 *            无 mods.toml，写 mcmod.info + @Mod(modid=..., version=...)
 *            无 CapabilityToken<>，用 @CapabilityInject 静态注入。
 *            Java 8 必须。
 */

/* ======================================================
 * §1 注册类（registry/*）
 * ======================================================
 *
 * 1.18.2- : JABDCreativeModeTabs.java 删除；
 *           在 JABDBlocks#register() 的 BlockItem 属性里加
 *               new Item.Properties().tab(CreativeModeTab.TAB_DECORATIONS)
 *
 * 1.16.5- : BlockBehaviour.Properties → AbstractBlock.Properties（同名但包名不同）
 *           Material.WOOL 不变；noOcclusion() 仍然 OK。
 *
 * 1.12.2 : 全部 GameRegistry.register：
 *
 *           public static final Block warm_bed = new WarmBedBlock(Material.CLOTH)
 *                   .setUnlocalizedName("warm_bed")
 *                   .setRegistryName(MOD_ID, "warm_bed")
 *                   .setCreativeTab(CreativeTabs.DECORATIONS);
 *           GameRegistry.register(warm_bed);
 *           GameRegistry.register(new ItemBlock(warm_bed).setRegistryName(warm_bed.getRegistryName()));
 *           // BlockEntity： GameRegistry.registerTileEntity(...)
 */

/* ======================================================
 * §2 Capability (DreamStateCapability)
 * ======================================================
 *
 * 1.16.5 : 把 CompoundTag → CompoundNBT；getResourceKey 无变化。
 *          CapabilityManager.INSTANCE.register(IDreamState.class,
 *              new Capability.IStorage<IDreamState>(){...}, DefaultDreamState::new);
 *          Provider 实现 ICapabilitySerializable<CompoundNBT>。
 *          事件名不变。
 *
 * 1.12.2 : 必须写 @CapabilityInject(IDreamState.class) 的静态字段 + 在 preInit
 *          中 CapabilityManager.INSTANCE.register(...)。
 *          事件名：AttachCapabilityEvent<Entity> 而非 AttachCapabilitiesEvent<Entity>。
 *          ICapabilitySerializable<NBTTagCompound>，NBTTagCompound / NBTBase
 *          PlayerEvent.Clone 的 isWasDeath 字段同。
 */

/* ======================================================
 * §3 命令系统（BadDreamCommands）
 * ======================================================
 *
 * 1.16.5 : Brigadier 仍可用；RegisterCommandsEvent 事件仍存在。
 *          EntityArgument.players() 同。
 *          SharedSuggestionProvider 改名叫 SuggestionProviders？不——名字相同。
 *          需注意返回 CommandSource（1.16.5 叫 CommandSource）。
 *
 * 1.12.2 : 必须改用 ICommand 接口；注册在 FMLServerStartingEvent 上：
 *
 *            @SubscribeEvent
 *            public void onServerStart(FMLServerStartingEvent e) {
 *                e.registerServerCommand(new BadDreamCommand()); // implements ICommand
 *            }
 *          getRequiredPermissionLevel 返回权限。
 *          Tab 补全自己实现 List<String> getTabCompletions(...)
 */

/* ======================================================
 * §4 配置（JABDConfig）
 * ======================================================
 *
 * 1.16.5 : ForgeConfigSpec 基本一致。ModConfigEvent 同。
 *
 * 1.12.2 : 使用 Configuration + Property：
 *            Configuration cfg = new Configuration(configFile);
 *            cfg.load();
 *            Property p = cfg.getCategory("trigger").get("bedTriggerMode")
 *                    .setComment("...").setDefaultValue("WAKE_UP");
 *            // FMLPreInitializationEvent 时 new Configuration(event.getSuggestedConfigurationFile())
 *            // FMLModIdMappingEvent / ConfigChangedEvent.OnConfigChangedEvent 重 load
 */

/* ======================================================
 * §5 事件与睡眠流程
 * ======================================================
 *
 * 1.16.5 : PlayerEvent.PlayerWakeUpEvent 存在。
 *          SleepingLocationCheckEvent 同。
 *          PlayerInteractEvent.RightClickBlock 同。
 *          LivingDeathEvent 同（priority/receiveCanceled 均支持）。
 *          PlayerEvent.PlayerStartSleepingEvent 在 1.16 叫 PlayerSleepInBedEvent。
 *              → 把 PlayerStartSleepingEvent 监听换成 PlayerSleepInBedEvent 即可。
 *
 * 1.12.2 :
 *          PlayerSleepInBedEvent → net.minecraftforge.event.entity.player.PlayerSleepInBedEvent
 *          没有 PlayerWakeUpEvent → 改用 EntityJoinWorldEvent 判断玩家 wasSleeping
 *              或 LivingEvent.LivingUpdateEvent 中判断 !player.isPlayerSleeping() 且上次为 true。
 *          BedBlock 继承方式相同（但 1.12 床没有 DyeColor 参数）。
 *          LivingDeathEvent 同，但 setCanceled(true) 的效果需加一层 EntityLiving#setHealth(max) 兜底，
 *              因为 1.12 取消事件后仍可能保留 deathTime>0 → onLivingUpdate 后续推进死亡。
 */

/* ======================================================
 * §6 备份 / 回档（BackupRollbackHandler）
 * ======================================================
 *
 * 1.16.5 : LevelResource 存在但 MinecraftServer#getWorldPath 可能是
 *          func_240776_a_(...)；可回退为：
 *              ((WorldServer)server.getWorld(DimensionType.OVERWORLD)).getChunkProvider()
 *                      .getChunkLoader().getWorldDir()
 *          saveAllNow → server.save(true, true, true)
 *          storageSource  →  server.field_240767_f_（通过反射）
 *
 * 1.12.2 : DimensionManager 管世界。备份目录直接
 *          new File(server.getFolderName(), "region").toPath()。
 *          saveAllNow → server.saveAllChunks(true, null)。
 *          回档时：DimensionManager.unloadWorlds(...) 后再覆盖文件，
 *          最后 DimensionManager.init() 重新加载世界并传送玩家。
 */

/* ======================================================
 * §7 资源文件（recipes / models / blockstates）
 * ======================================================
 *
 * 1.16.5 : 资源格式与 1.20.1 几乎一致（recipes/blockstates/models 均为 json v1），
 *          但 lang 文件必须是 .lang（key=value）格式！
 *          → assets/jabbadream/lang/en_us.lang：
 *              tile.warm_bed.name=Warm Bed
 *              itemGroup.jabbadream=Just A Bad Dream
 *
 * 1.12.2 : 资源写在 src/main/resources/assets/ 下，
 *          blockstates JSON 仍可用；但模型需使用 "parent": "block/bed_head" 等。
 *          recipes 放在 src/main/resources/assets/jabbadream/recipes/warm_bed.json
 *          且 1.12 不支持 data/* 包（Data Pack 机制不存在），
 *          必须用 GameRegistry.addRecipe(new ShapedOreRecipe(...)) 注册。
 */

/* ======================================================
 * §8 调试：日志/问题定位
 * ======================================================
 *
 * 所有版本通用：若回档没触发，按序检查：
 *   1. /baddream status 确认叠加态 active + backupId 非空
 *   2. 查看服务器 latest.log 里 [JABD] 的日志（备份、回档都会打）
 *   3. 打开 world/jabbadream_backups/players/<uuid>/ 看是否有备份目录
 *   4. JABDConfig.COMMON.checkTotemOnRealDeath 是否 true → 有图腾会阻止回档
 *   5. 其他模组在 LivingDeathEvent 更 LOWEST 优先级取消了事件？
 *      → 开启 /baddream config totem false 测试能否触发
 */
