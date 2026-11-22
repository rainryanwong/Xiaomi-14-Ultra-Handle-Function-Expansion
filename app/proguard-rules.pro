# ============ HandleFix LSPosed 模块 R8 规则 ============

# ---- Xposed 入口（assets/xposed_init 通过类名反射加载）----
-keep class com.handlefix.HandleFixModule { *; }
-keep class com.handlefix.system.SystemHandleFix { *; }

# ---- 业务代码整体不混淆 ----
# hook 逻辑大量依赖静态字段/跨类调用与 Xposed 反射，保留可避免类名/方法名
# 被改写后引发隐蔽的“找不到类/方法”问题（体积大头是依赖库，不是本包代码）
-keep class com.handlefix.** { *; }

# ---- compileOnly 的 Xposed/LSPosed API（运行时不打包，R8 分析无需警告）----
-dontwarn de.robv.android.xposed.**
-dontwarn org.lsposed.lspd.**
