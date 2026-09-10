# 脚本 APK 签名

打包页（`MiuixBuildActivity`）的「签名」组决定产物用哪张证书签名，直接影响：

- 应用身份是否唯一（能不能和别人的包冲突、能不能被安全软件识别为「家族」）
- 后续版本能不能覆盖安装（Android 只允许**同一证书**的包互相升级）

## 现状：不再使用全世界共用的公共证书

打包时如果没有显式选签名（打包页的「自动签名」），由 `AutoSigningIdentity` 在**产物旁边**
生成/复用 `<应用名>-signing.p12`：身份只属于你这台设备上的这个应用，口令记在应用私有偏好里。
同一个应用名反复打包复用同一份身份，可以正常覆盖升级；产物是 **v1 + v2** 双签名。

历史遗留的 `pxb.android.tinysign.TinySign` 把证书和私钥 **硬编码在库里**，曾经是我们的默认签名：

| 项 | 值 |
| --- | --- |
| Subject | `CN=Test` |
| SHA1 | `55:5B:E3:57:FD:FE:3D:0F:A4:2B:B3:37:81:11:7A:CC:DB:11:9A:B2` |
| SHA256 | `E1:F6:ED:B5:A6:5A:79:E6:9F:9F:41:A4:4B:3B:5D:5B:B9:59:54:B3:4D:32:0A:52:D3:F7:B6:3E:64:7B:0A:43` |

所有用 tiny-sign / 同源打包器（Auto.js Pro、AutoX.js 的默认分支）产出的 APK 共用同一份私钥，
产物只是 v1-only（`apksigner verify` 显示 `v2: false`），MANIFEST 里还留着 `Created-By: tiny-sign-null`。
这几个特征都可以被安全软件当作指纹（实测：同一模板换成本机专属身份后，MIUI 安全中心不再报毒）。

现在 `ApkPackager.repackage()` 在**没有签名器时直接报错**，不会再静默退回那份证书；
默认路径由 `ApkBuilder.sign()` → `AutoSigningIdentity` 提供。

## 打包页的三个选项

| 选项 | 行为 |
| --- | --- |
| 自动签名 | 默认。首次打包时为该应用生成专属身份（`<应用名>-signing.p12`，放在产物旁边），之后固定复用 |
| 选择签名 | 用你自己的密钥库（`.jks` / `.keystore` / `.p12` / `.bks`） |
| 新建签名 | 显式生成一份（生成后等同于选择签名，只是会把口令回填给你保存） |

卡片上始终有一行「**当前签名：…**」把即将使用的身份写出来：

- `内置测试证书 CN=Test（全世界共用）` —— 最容易被忽略的选项，用红色标出
- `<你的证书主题>` —— 验证通过后显示为强调色
- `尚未验证，打包仍会退回内置证书` —— 填了口令但没点「验证密钥」时会走到这里

选择、密钥库路径、别名、口令都会写进应用私有 SharedPreferences，**下次进页面不会默默退回默认签名**
（与 AutoX.js 存 keystore 口令的做法一致）。自动生成的口令还会**按密钥库路径单独记账**，
这样自动签名能在下次打包时找回同一份身份；早期版本只把口令记在「当前密钥库口令」一个键上，
`AutoSigningIdentity` 会把它当备选口令试一次并补记账，升级上来的用户不用手动重填。

要是两个口令都打不开那份密钥库（口令丢了、或者文件不是本机生成的），**不会把打包堵死**：
旧文件改名成 `<名字>-signing.p12.unreadable` 备份一份，再生成一套新身份继续打包，
成功弹框里会用红字说明这件事 —— 用过旧身份的已装应用需要先卸载。

> 写测试时注意：instrumented 测试跑在应用真实进程里，直接写 `Pref` 会改到用户的真实数据，
> 必须备份并在 `finally` 里还原（曾因此把用户密钥库的口令覆盖掉，导致打包报「没有它的口令」）。打包成功后弹框还会多一行「签名：…」，
它读的是**产物文件里真实的证书**（`ApkSignatureReader`），而不是界面上的配置项。

换证书后**旧证书签的包无法覆盖安装**（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），需先卸载；
这是 Android 的机制，不是 bug。

> 踩过的坑：签名模式判定曾经写成「只认『选择签名』」，导致「新建签名」生成出来的密钥库
> 根本没参与打包，产物依旧是内置的 `CN=Test`。现在这段判断被抽到 `SigningOptions.signerFor()`，
> 由 `ApkBuilderEncryptionTest.signingModeDecidesWhetherTheCustomKeyIsUsed` 盯住。
> 验证是否真的生效，永远以产物为准：`apksigner verify --print-certs <apk>`。

## 实现

签名引擎在 `apps/app/src/main/java/com/jdkshen/aijspro/autojs/build/sign/`，纯 Java、无第三方依赖
（apksig 依赖 `java.util.function` / `java.util.Base64`，在 API 21 上跑不起来；BouncyCastle 体积过大）：

| 类 | 职责 |
| --- | --- |
| `SigningKey` | 私钥 + 证书链 + 算法/OID 映射；支持 PKCS12 / JKS / BKS，别名留空自动取第一个私钥条目 |
| `KeyStoreGenerator` | 生成自签名身份并存成 PKCS#12 |
| `DerWriter` | 最小 ASN.1 DER 编码器 |
| `ApkSignerV1` | `META-INF/MANIFEST.MF`、`CERT.SF`、`CERT.RSA`（PKCS#7 SignedData） |
| `ApkSignerV2` | APK Signature Scheme v2：内容摘要 + 签名块 |
| `KeyStoreApkSigner` | 编排：按工作区重新打 zip → 写 v1 文件 → 原地插入 v2 签名块 |
| `SigningOptions` | 打包页的选择逻辑（哪个模式用哪个签名器）、密钥库命名与口令记账 |
| `AutoSigningIdentity` | 默认身份：按应用生成/复用 `<应用名>-signing.p12` |
| `ApkSignatureReader` | 从**已打包的 APK** 里读回真实签名者，用于打包成功提示与回归测试 |

接入点是一个很小的接口 `com.stardust.autojs.apkbuilder.Signer`：`ApkBuilder.sign()` 会先取
`AppConfig` 里用户显式选的签名器，没选就交给 `AutoSigningIdentity` 按应用生成/复用身份，
再交给 `ApkPackager.setSigner()`；**打包器拿不到签名器时会直接报错**，不再存在「悄悄用公共证书」的退路。

### 产物格式要点（改动前务必先读）

**v1（JAR 签名）**

- `MANIFEST.MF`：每条目一段，`SHA-256-Digest` 是条目**解压后内容**的摘要
- `CERT.SF`：`SHA-256-Digest-Manifest` = 整个 MANIFEST.MF 字节的摘要；同时写
  `X-Android-APK-Signed: 2`（v1+v2 同时存在时的防回滚要求）
- `CERT.RSA`：必须是 PKCS#7 SignedData。`SignerInfo.digestAlgorithm` 要写**纯摘要 OID**
  `2.16.840.1.101.3.4.2.1`——Android 的 `ObjectIdentifier.getName()` 只把它映射成 `SHA-256`，
  写成 `sha256WithRSA` 会导致 `MessageDigest.getInstance()` 直接失败；签名算法用 `rsaEncryption`
  （`1.2.840.113549.1.1.1`），与 tiny-sign / apksig 的既有产物一致
- 目录条目（`xxx/`）不需要出现在 MANIFEST.MF 里

**v2（APK Signature Scheme v2）**

- 签名块插在「条目数据」和「ZIP 中央目录」之间，magic 为 `APK Sig Block 42`，
  首尾两个 `uint64 size` 的值 = 块长度 - 8
- 内容摘要是两级 Merkle 树：三个区段（条目数据 / 中央目录 / EOCD）各自按 1MiB 分块，
  每块算 `SHA-256(0xa5 || uint32le(块长) || 块)`，最后
  `SHA-256(0x5a || uint32le(块总数) || 各块摘要拼接)`
- 对**未签名**的包算摘要即可：那时 EOCD 里的「中央目录偏移量」本来就等于签名块将要插入的位置，
  校验方也会把该字段当作签名块偏移量来重算，所以不存在「先有块大小还是先有摘要」的循环依赖
- v2 分块的字段是「长度前缀的序列，元素再各自带长度前缀」，容易写错；`0x0103` 是
  **RSASSA-PKCS1-v1_5 + SHA2-256**（`0x0101` 才是 PSS）

## 校验方式

```powershell
# 官方校验器（build-tools 34 才有 apksigner，28.0.3 会静默失败）
C:\Android\build-tools\34.0.0\apksigner.bat verify --verbose --print-certs <apk>

# v1 单独校验
& "$env:JAVA_HOME\bin\jarsigner.exe" -verify <apk>

# 真机仪器测试（覆盖：生成密钥 → PKCS#12 往返 → 打包 → 平台校验证书 → JarFile 逐条目校验 → v2 块存在
#   → 默认签名必须不是公共证书、同一应用必须复用同一身份）
adb shell am instrument -w -e class com.jdkshen.aijspro.packaging.ApkBuilderEncryptionTest \
  com.jdkshen.aijspro.test/androidx.test.runner.AndroidJUnitRunner
```
