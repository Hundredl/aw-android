package net.activitywatch.android.ledger

import org.junit.Assert.*
import org.junit.Test

class ContentPrivacyTest {
    @Test fun incognitoIsOffByDefault() {
        for (label in listOf("Incognito mode", "无痕浏览", "隐身窗口")) assertTrue(ContentPrivacy.blocks(label))
    }
    @Test fun optInAllowsIncognitoLabels() {
        for (label in listOf("Incognito mode", "无痕浏览", "隐身窗口")) assertFalse(ContentPrivacy.blocks(label, true))
    }
    @Test fun optInCannotOverrideSensitiveContent() {
        for (label in listOf("password", "密码", "验证码", "credit card", "PRIVATE_TEST")) {
            assertTrue(ContentPrivacy.blocks("Incognito mode $label", true))
            assertTrue(ContentPrivacy.blocks(label, false))
        }
    }
    @Test fun ordinaryPagesRemainEligibleEitherWay() {
        assertFalse(ContentPrivacy.blocks("阅读植物养护文章"))
        assertFalse(ContentPrivacy.blocks("阅读植物养护文章", true))
    }
}
