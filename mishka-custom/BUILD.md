# 构建

## 前置环境

| 依赖 | 版本要求 | 说明 |
| --- | --- | --- |
| JDK | **21+** | `sourceCompatibility = 21`；Gradle 9.7.1 + AGP 9.4.1 |
| Android SDK | Platform **37**（`COMPILE_SDK=37`, `MIN_SDK=31`, `TARGET_SDK=37`） | 用 Android Studio 或 `sdkmanager "platforms;android-37"` |
| Android NDK | AGP 9.4.1 的默认版本 | 原生构建（`libmishka_jni.so` 等）要用；缺失时 AGP 会尝试自动下载，也需要接受 licenses |
| Go | **1.25+** | `GoBuildTask` 用它交叉编译 `libmihomo.so`（`GOOS=android`，cgo 走 NDK clang） |
| git / bash / python3 | 任意较新版本 | 脚本与自检工具用 |
| 磁盘 | 约 10 GB（Gradle 缓存 + 内核 + 产物） | 首次构建依赖下载最多 |

## 本地构建（只出 release）

```bash
cd <你的 Mishka 仓库>

# 首次：装配（换内核 + 打补丁 + 生成 go.work + app 补丁）
bash mishka-custom/scripts/setup.sh --repo .

# 构建（内部先 :app:downloadGeoFiles，再 :app:assembleRelease）
bash mishka-custom/scripts/build-release.sh --repo .
```

产物：`app/build/outputs/apk/release/*.apk`，脚本会打印路径、体积与 sha256。

常用开关：

| 开关 | 作用 |
| --- | --- |
| `--clean` | 先 `clean` 再构建 |
| `--refresh-sum` | 构建前跑 `go mod download all` 补 `go.work.sum`（上游加依赖后需要） |
| `--unsigned` | 不配签名（APK 装不上，仅作对比体积用） |
| `--no-keystore` | 没有 keystore 时不自动生成，直接报错 |

> 内存小的机器：`org.gradle.jvmargs=-Xmx4g` 写在仓库的 `gradle.properties` 里（本定制不动它），
> 临时调小可以加 `-Dorg.gradle.jvmargs=-Xmx3g`。

## 签名

三种方式，任选其一：

1. **自动生成**（最省事）：什么都不做，`build-release.sh` 发现没有签名配置时会调
   `scripts/gen-keystore.sh`，在 `mishka-custom/keystore/mishka-release.jks` 生成一个 4096 位 RSA 密钥，
   并把 `KEYSTORE_PATH` / `KEYSTORE_PASS` / `KEY_ALIAS` / `KEY_PASSWORD` 写进 `local.properties`
   （该文件在 `.gitignore` 里）。
2. **用自己的 keystore**：
   ```bash
   bash mishka-custom/scripts/gen-keystore.sh --repo . --force \
        --alias mykey --store-pass '口令' --key-pass '口令' --dname 'CN=My,OU=Self,O=Self,L=City,C=CN'
   # 或直接把现成的 keystore 路径/口令写进 local.properties 的四个键里
   ```
3. **CI secrets**：见下节，仓库 secrets `KEYSTORE_BASE64`（keystore 的 base64）、`KEYSTORE_PASS`、
   `KEY_ALIAS`、`KEY_PASSWORD`。

> ⚠️ keystore 一旦用来发布就别再换：换了签名，已安装的用户只能**卸载重装**。
> 备份 `mishka-custom/keystore/` 与 `local.properties` 里的口令。

## CI（GitHub Actions，只出 release）

```bash
mkdir -p .github/workflows
cp mishka-custom/ci/build-release.yml .github/workflows/release.yml
git add mishka-custom .github/workflows/release.yml
git commit -m "ci: 只构建 release 的自定义工作流（jieluojun 内核 + 锚点面板）"
git push
```

* 需要提交的是 **`mishka-custom/`（新增）与这个工作流文件**；
  **不要**提交 `app/src/main/kotlin/.../custom/`、也不要提交 `go.work` / `go.work.sum` /
  `FileManagerEditorScreen.kt` 的改动 —— 那些都由工作流里的 `setup.sh` 现场生成。
* 触发：手动 `workflow_dispatch`，或推 `v*` tag（推 tag 时会自动把 APK 挂到 GitHub Release）。
* 工作流不做 debug 构建；`setup.sh --ci` 会自行把 `mihomo` 换成 jieluojun 内核（不拉上游 mihomo 子模块）。
* 没配 `KEYSTORE_BASE64` 时会用固定参数的 debug 风格密钥签名，并在 Summary 里提示：
  能直接安装也能覆盖升级，但私钥公开，只适合自用；换成正式 keystore 后用户需要卸载重装。

## fork 结构要求（CI 报错多半出在这里）

工作流跑的是**你 fork 仓库里的内容**，所以 fork 必须满足三件事，缺一个就失败：

| 要求 | 检查方式 | 被破坏时 CI 的报错 |
| --- | --- | --- |
| `mishka-custom/` 是**普通目录**、内容完整 | GitHub 上能直接看到 `mishka-custom/scripts/setup.sh` | `bash: mishka-custom/scripts/setup.sh: No such file or directory`（它被提交成了「子模块指针」，网页上只会显示成一个空目录/链接） |
| `scripta` 是可用的**源码或子模块指针** | 仓库里有 `scripta` 子模块条目，或工作流能 clone 到 pin | `Checkout scripta submodule` 步骤失败：`pathspec 'scripta' did not match` |
| `gradlew` 有**可执行位**（100755） | 本地 `git ls-files -s gradlew` 应以 `100755` 开头 | `./gradlew: Permission denied` |

另外，**重新 fork / 网页上传 / 打 zip 再解压提交**会同时毁掉这三样（子模块指针丢失、gradlew 变成 644、
上游文件缺失），此时分支历史也往往和上游脱钩（例如只有一条 `first commit`）。**推荐做法**：
用 GitHub 的 Fork 按钮从上游 fork（历史、子模块指针、文件权限都完整），再往上叠 `mishka-custom/` 与工作流文件。

已经损伤的 fork，可以本地修：

```bash
# 预览要做什么（不动任何东西）
bash mishka-custom/scripts/repair-fork.sh --repo . --dry-run

# 真修：恢复 scripta/mihomo 子模块指针、gradlew 权限位、缺失的上游文件
bash mishka-custom/scripts/repair-fork.sh --repo .
git submodule update --init scripta
git add -A && git commit -m "fix: 恢复子模块指针与 gradlew 权限位" && git push
```

> 上游的 `.github/workflows/build.yml` 如果被删了，脚本只提示不自动恢复（它会在 push 时构建
> debug + release 两个包）。想恢复：`git checkout FETCH_HEAD -- .github/workflows/build.yml`；
> 不想让它跑就在仓库 Actions 页面里禁用该工作流（纯 UI 操作，不动文件）。

工作流自带三道自检（顺序在「换内核」之前），任何一条不满足都会**直接失败并打印修复命令**，
不会让你对着 Gradle 的报错猜：mishka-custom 齐全性、scripta 可获取、gradlew 可执行位。

## 排错

| 现象 | 原因 / 处理 |
| --- | --- |
| `missing go.work.sum entry` / `missing go.sum entry` / `updates to go.mod needed` | 依赖哈希不全。跑 `bash mishka-custom/scripts/setup.sh --repo . --refresh-sum`（需要联网）后重试。根因见 `kernel/README.md` |
| `NDK clang wrapper not found at ...` | NDK 没装或版本不对：装 AGP 默认版本，或接受 licenses 让 AGP 自动下载 |
| `Unsupported class file major version` / `Unsupported Java` | JDK 版本低于 21：`export JAVA_HOME=<jdk-21>` |
| 构建成功但没有 APK | 看 `mishka-custom/build.log`；产物在 `app/build/outputs/apk/release/` |
| `error: no space left on device` | 沙箱/容器常见坑：把 `TMPDIR`/`GOTMPDIR` 指到大盘上再跑 |
| 改完内核补丁后产物没变 | 内核目录是 `GoBuildTask` 的输入（`replacedModuleSources`），改了就应重建；如怀疑缓存，加 `--clean` 或删 `.gradle`/`build` |
| 安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名与已装版本不同：卸载旧的再装（或换回原签名 keystore） |

## 体积参考

* release：开了 R8 + 资源剥离 + 只打 `arm64-v8a`，具体数字以你本地构建为准；
* debug：明显更大（本定制的所有构建入口都不再构建它）。

想看两边差距，可以临时：
`./gradlew -I mishka-custom/init/no-debug.init.gradle :app:assembleDebug -PallowDebugBuild=true`
