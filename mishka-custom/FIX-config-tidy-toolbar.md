# 修复：字段整理按钮位置（文件名左侧）

补丁：`patches/app/0007-config-tidy-toolbar.patch`（依赖 0001–0006）

## 变更

- 将 YAML 编辑器的 Sort 图标按钮从顶栏右侧 `actions` 移入 `navigationIcon`，紧跟返回键，因此显示在 `config.yaml` 文件名左侧。整理字段、提示文案与保存校验流程不变；“配置表单”和“锚点面板”仍留在右侧。
- 严格遵循“不引入新依赖”：本补丁只使用项目现有 Compose API。由于公开平台 API 没有等价的单 WebView 代理覆盖接口，原 0007 外网代理路由补丁不再纳入本次交付；因此本次不承诺 WebView 外网请求会显式经 mihomo 转发。

## 验证

- `tools/verify_app_patch.sh --repo <Mishka 仓库> --series` 按 0001–0009 顺序应用、运行断言并逆序回滚，最终要求工作区干净。
- 断言里除了 `fun tidyConfig()`（整理行为仍可复用）之外，还有一条结构检查：Sort 图标必须出现在 `navigationIcon` 里、且不再出现在 `actions` 里。
- `tools/check_cross_package_imports.py`、`tools/check_trailing_lambda.py` 继续纳入整套静态检查。
- Android 编译与真机验收需在具备 Gradle 依赖缓存和 Android 设备的环境中完成。

> 面板（Web 界面）相关的修补已全部从 0006 / 0007 里剥离，不再留在这条补丁序列上；现行方案
> 见 `FIX-panel-standalone-activity.md`。
