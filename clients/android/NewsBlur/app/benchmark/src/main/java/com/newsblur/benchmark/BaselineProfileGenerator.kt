package com.newsblur.benchmark

import androidx.benchmark.macro.ExperimentalBaselineProfilesApi
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalBaselineProfilesApi::class)
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generateSimpleStartupProfile() {
        rule.collectBaselineProfile(packageName = "com.newsblur") {
            pressHome()
            startActivityAndWait()
        }
    }

    @Test
    fun generateUserJourneyProfile() {
        var needsLogin = true
        rule.collectBaselineProfile(packageName = "com.newsblur") {
            pressHome()
            startActivityAndWait()

            if (needsLogin) {
                inputIntoLabel("username", "android_speed")
                inputIntoLabel("password", "newsblur")
                clickOnText("LOGIN")
                needsLogin = false
                waitForTextShown("android_speed")
            }

            waitLongForTextShown("Android Authority")
            clickOnText("All Stories")
            waitForTextShown("All Stories")
        }
    }
}