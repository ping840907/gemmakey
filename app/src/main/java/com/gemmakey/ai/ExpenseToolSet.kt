package com.gemmakey.ai

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.gemmakey.model.ExpenseCategory
import com.gemmakey.model.ExpenseType
import com.gemmakey.model.ParsedExpense

/**
 * Gemma 4 反射式 Function Calling 工具集（對照 Gallery AgentTools.kt）。
 *
 * 使用方式：
 *   val toolSet = ExpenseToolSet()
 *   val conv = engine.createConversation(ConversationConfig(tools = toolSet))
 *
 * 模型呼叫 record_expense() 時：
 *   - toolSet.lastCall 持有解析結果
 *   - ViewModel 偵測到後彈出確認 dialog
 */
class ExpenseToolSet : ToolSet {

    /** 最後一次工具呼叫的解析結果，ViewModel 消費後應清空 */
    @Volatile var lastCall: ParsedExpense? = null
        private set

    @Tool(description = "記錄一筆支出或收入到記帳系統，並顯示確認表格讓用戶核對後儲存")
    fun record_expense(
        @ToolParam(description = "金額，正數數字，不含貨幣符號")
        amount: Double,

        @ToolParam(description = "交易類型：EXPENSE 為支出，INCOME 為收入")
        type: String,

        @ToolParam(
            description = "費用類別，從以下選擇：" +
                "FOOD（餐飲）、TRANSPORT（交通）、SHOPPING（購物）、" +
                "ENTERTAINMENT（娛樂）、HEALTH（醫療）、EDUCATION（教育）、" +
                "UTILITIES（帳單）、HOUSING（住宿）、SALARY（薪資）、" +
                "BONUS（獎金）、INVESTMENT（投資）、OTHER（其他）"
        )
        category: String,

        @ToolParam(description = "10 字以內的簡短說明")
        description: String
    ): Map<String, String> {
        return if (amount <= 0) {
            mapOf("status" to "error", "message" to "金額必須大於零")
        } else {
            lastCall = ParsedExpense(
                amount = amount,
                type = if (type.trim().uppercase() == "INCOME")
                    ExpenseType.INCOME else ExpenseType.EXPENSE,
                category = ExpenseCategory.fromString(category.trim()),
                description = description.trim()
            )
            // 回傳 "awaiting_confirmation"，ViewModel 偵測到後顯示 dialog
            // 模型收到此結果後會輸出「請確認以下記帳內容」之類的提示
            mapOf(
                "status"  to "awaiting_confirmation",
                "message" to "已偵測到記帳內容，請用戶確認後儲存"
            )
        }
    }

    fun clearLastCall() { lastCall = null }
}
