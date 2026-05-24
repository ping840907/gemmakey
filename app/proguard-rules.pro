# LiteRT-LM：保留所有推論引擎類別（反射式 @Tool 需要保留方法名稱）
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# App 的 AI 層：@Tool/@ToolParam 反射需要保留方法
-keep class com.moneytalks.ai.** { *; }
# 保留 @Tool 標記的方法（避免 R8 把 record_expense 等方法混淆）
-keepclassmembers class com.moneytalks.ai.ExpenseToolSet {
    @com.google.ai.edge.litertlm.Tool <methods>;
}

# Room 資料層
-keep class com.moneytalks.data.** { *; }
-keep class com.moneytalks.model.** { *; }
