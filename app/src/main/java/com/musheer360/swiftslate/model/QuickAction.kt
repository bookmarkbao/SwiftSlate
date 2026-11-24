package com.musheer360.swiftslate.model

/**
 * One action offered by the floating AI panel. [prompt] is sent as the transformation
 * directive (see ApiClientUtils.SYSTEM_PROMPT_PREFIX); [label] is what the chip shows.
 */
data class QuickAction(val id: String, val label: String, val prompt: String)

object QuickActions {

    private const val KEEP = "保持原文的语言、核心意思，保留人名、数字、链接等事实信息，不要编造新内容。只输出改写后的文字，不要解释。"

    val PRESETS: List<QuickAction> = listOf(
        QuickAction("polish", "润色", "润色这段文字，让表达更清晰、通顺、自然。$KEEP"),
        QuickAction("fix", "纠错", "只修正错别字、语法和标点错误，其余内容保持不变。只输出修正后的文字。"),
        QuickAction("polite", "更礼貌", "改写得更礼貌、得体、委婉，适合发给同事、客户或长辈。$KEEP"),
        QuickAction("short", "更简洁", "在保留核心意思的前提下改写得更简洁，删掉啰嗦和重复。$KEEP"),
        QuickAction("casual", "口语化", "改写成自然、轻松的聊天口吻，像朋友之间发微信一样。$KEEP"),
        QuickAction("formal", "正式", "改写成正式、专业的书面语气，适合工作场合。$KEEP"),
        QuickAction("expand", "扩写", "把这段文字写得更具体、完整、有条理，只能展开原文已有的内容，不要编造事实。只输出改写后的文字。"),
        QuickAction("to_en", "译英", "Translate the text into natural, fluent English. Output only the translation."),
        QuickAction("to_zh", "译中", "把这段文字翻译成自然流畅的简体中文。只输出译文。")
    )

    val DEFAULT: QuickAction = PRESETS.first()
}
