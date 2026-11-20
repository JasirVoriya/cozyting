# CastService 构造期 NPE 修复（0.9.2）

## 崩溃

- 设备：Android 15+ 实机；包 `com.miplay.sender.acceptance` 0.9.1（versionCode 10）
- 勾选 L17A + MediaProjection 授权后，`CastService` 启动即崩：

```
Unable to create service com.miplay.sender.CastService
Caused by: java.lang.NullPointerException:
  Attempt to invoke virtual method 'android.content.Context
  android.content.Context.getApplicationContext()' on a null object reference
    at ContextWrapper.getApplicationContext
    at AndroidStreamMusicVolume.<init>(AndroidStreamMusicVolume.kt:8)
    at CastService.<init>(CastService.kt:106)
```

## 根因

`CastService` 属性初始化器：

```kotlin
private val localMute = LocalOutputMute(AndroidStreamMusicVolume(this))
```

在 `attachBaseContext` / `onCreate` **之前**执行；此时 Service 的 base context 仍为 null，`getApplicationContext()` NPE。

## 修法

1. `localMute` 改为 `lateinit`，在 `onCreate()` 中：

   `LocalOutputMute(AndroidStreamMusicVolume.fromAttachedContext(applicationContext))`

2. `AndroidStreamMusicVolume` 改为注入已取得的 `AudioManager`；工厂方法只接受**已附加**的 Context，不再在 Service 构造路径调用 `getApplicationContext()`。

## 回归

`CastServiceMuteInitTest`：`CastService()` 构造不得触碰 `applicationContext` / 不得已初始化 `localMute`。

## 构建

- versionCode **11**，versionName **0.9.2-cast-panel-acceptance**
- applicationId 仍为 `com.miplay.sender.acceptance`；验收签名不变
- 未改 UI / 路由 / 协议标 / 静音探针逻辑
