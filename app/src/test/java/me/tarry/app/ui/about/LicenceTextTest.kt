package me.tarry.app.ui.about

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LicenceTextTest {
    @Test
    fun joinsHardWrappedLinesIntoOneParagraph() {
        val text = "Licensed under the Apache License,\nVersion 2.0 (the \"License\");\nyou may not use this file."
        assertThat(reflowLicenceText(text)).isEqualTo("Licensed under the Apache License, Version 2.0 (the \"License\"); you may not use this file.")
    }

    @Test
    fun keepsBlankLineParagraphBreaks() {
        assertThat(reflowLicenceText("First line\nstill first.\n\nSecond\nparagraph.")).isEqualTo("First line still first.\n\nSecond paragraph.")
    }

    @Test
    fun keepsListAndIndentedLinesOnTheirOwnLines() {
        val text = "Terms:\n* one\n* two\n- three\n1. four\n  indented\nafter\nlist."
        assertThat(reflowLicenceText(text)).isEqualTo("Terms:\n* one\n* two\n- three\n1. four\n  indented\nafter list.")
    }

    @Test
    fun handlesWindowsLineEndingsAndEmptyText() {
        assertThat(reflowLicenceText("a\r\nb")).isEqualTo("a b")
        assertThat(reflowLicenceText("")).isEqualTo("")
    }
}
