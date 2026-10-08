# 修复：字段整理工具栏位置与面板重进黑屏

补丁：`patches/app/0007-config-tidy-toolbar-and-panel-reentry.patch`（依赖 0001–0006）

## 变更

- 将 YAML 编辑器的 Sort 图标按钮从顶栏右侧 `actions` 移入 `navigationIcon`，紧跟返回键，因此显示在 `config.yaml` 文件名左侧。整理字段、提示文案与保存校验流程不变；“配置表单”和“锚点面板”仍留在右侧。
- WebView 的 `contentVisible` 是 Compose 的瞬态状态，离开面板后会重建；只依赖它会把已经渲染好的缓存 WebView 再次设成透明。现在把 `contentCommitted` 写入 WebView 自身的 `tag`，跨 Compose 生命周期保留。
- **仅应用 0007 时**：若缓存页入口 URL / `reloadKey` 匹配且已提交则直接显示原 WebView；未提交或地址变化时才从入口重载。完整交付会再叠加 0008，最终行为以 0008 为准：每次重进都从入口 URL 开始。
- 缓存实例重新挂载时同步一次 `canGoBack`，避免新建的宿主状态与 WebView 历史状态不一致。
- **最终重进行为由后续 0008 收紧**：仍缓存 WebView 实例，但每次重新进入都重载所选面板的入口 URL、清空旧 history；不会沿用上次停留的 SPA tab / 子页。见 `FIX-panel-default-page-reentry.md`。
- 严格遵循“不引入新依赖”：本补丁只使用项目现有 Compose 与 `android.webkit` API。由于公开平台 API 没有等价的单 WebView 代理覆盖接口，原 0007 外网代理路由补丁不再纳入本次交付；因此本次不承诺 WebView 外网请求会显式经 mihomo 转发。

## 验证

- `tools/verify_app_patch.sh --repo <Mishka 仓库> --series` 按 0001–0008 顺序应用、运行断言并逆序回滚，最终要求工作区干净。
- `tools/check_cross_package_imports.py`、`tools/check_trailing_lambda.py` 继续纳入整套静态检查。
- Android 编译与真机重进验收需在具备 Gradle 依赖缓存和 Android 设备的环境中完成。
