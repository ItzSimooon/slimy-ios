package de.hyprnode.slimy.ui.screens.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import de.hyprnode.slimy.art.PixelSlime
import de.hyprnode.slimy.model.Brand
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.SpriteAnim
import de.hyprnode.slimy.model.Stage
import de.hyprnode.slimy.online.FriendsStore
import de.hyprnode.slimy.ui.kit.GroupedDivider
import de.hyprnode.slimy.ui.kit.GroupedRow
import de.hyprnode.slimy.ui.kit.GroupedSection
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.screens.common.PushedScaffold
import de.hyprnode.slimy.ui.screens.common.ScreenColumn
import de.hyprnode.slimy.ui.screens.common.openLink
import de.hyprnode.slimy.ui.screens.common.shareText

private const val website = "https://slime.hyprnode.de"

/** Version, who makes Slimy, links, licenses and acknowledgements (App/UI/Screens/AboutView.swift). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onWhatsNew: () -> Unit, onLicense: () -> Unit) {
    val context = LocalContext.current
    val (version, build) = remember { AppVersion.of(context) }

    PushedScaffold(title = "About", onBack = onBack) { padding ->
        ScreenColumn(padding, spacing = 20.dp) {
            AboutHeader()
            GroupedSection {
                GroupedRow(
                    title = "Version",
                    modifier = Modifier
                        .combinedClickable(
                            role = Role.Button,
                            onLongClickLabel = "Copy version",
                            onLongClick = { copy(context, "Slimy $version ($build)") },
                            onClick = {},
                        )
                        .semantics(mergeDescendants = true) { },
                    trailing = { Value(version) },
                )
                GroupedDivider()
                GroupedRow(title = "Build", modifier = Modifier.semantics(mergeDescendants = true) { }, trailing = { Value(build) })
                GroupedDivider()
                GroupedRow(title = "Made by", modifier = Modifier.semantics(mergeDescendants = true) { }, trailing = { Value("hyprnode") })
                GroupedDivider()
                GroupedRow(
                    title = "What's New in Slimy ${WhatsNew.version}",
                    titleColor = Theme.accent,
                    icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Theme.accent) },
                    onClick = onWhatsNew,
                )
            }
            GroupedSection(header = "Links") {
                Link("Website", Icons.Filled.Language) { openLink(context, website) }
                GroupedDivider(inset = 56.dp)
                Link("Support", Icons.Outlined.SupportAgent) { openLink(context, FriendsStore.supportURL) }
                GroupedDivider(inset = 56.dp)
                Link("Privacy Policy", Icons.Outlined.PanTool) { openLink(context, FriendsStore.privacyURL) }
                GroupedDivider(inset = 56.dp)
                Link("Terms of Use", Icons.Outlined.Description) { openLink(context, FriendsStore.termsURL) }
                GroupedDivider(inset = 56.dp)
                GroupedRow(
                    title = "Share Slimy",
                    titleColor = Theme.accent,
                    icon = { Icon(Icons.Filled.Share, contentDescription = null, tint = Theme.accent) },
                    onClick = { shareText(context, "Slimy: a tiny slime that lives in your notifications. $website") },
                )
            }
            GroupedSection(header = "Open-source licenses") {
                GroupedRow(title = "Pixelify Sans", subtitle = "SIL Open Font License 1.1", onClick = onLicense)
            }
            GroupedSection(header = "Acknowledgements", footer = "© 2026 hyprnode") {
                Credit("Pixelify Sans", "By the Pixelify Sans Project Authors. The slime's voice and the wordmark.")
                GroupedDivider()
                Credit("AI replies", "Talking with your slime uses Cloudflare Workers AI and Groq.")
                GroupedDivider()
                Credit("Art and sound", "The pixel art, animations, sounds and music were made for Slimy.")
            }
        }
    }
}

@Composable
private fun Value(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Theme.inkSoft)
}

@Composable
private fun Link(title: String, icon: ImageVector, onClick: () -> Unit) {
    GroupedRow(
        title = title,
        titleColor = Theme.accent,
        icon = { Icon(icon, contentDescription = null, tint = Theme.accent) },
        trailing = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Theme.inkSoft, modifier = Modifier.size(18.dp)) },
        onClickLabel = "Open in your browser",
        onClick = onClick,
    )
}

@Composable
private fun Credit(title: String, detail: String) {
    GroupedRow(title = title, subtitle = detail, modifier = Modifier.semantics(mergeDescendants = true) { })
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Slimy version", text))
}

/** The app icon, the wordmark and the tagline, at the top of About. */
@Composable
private fun AboutHeader() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AboutAppIcon(96.dp)
        Row(
            Modifier.clearAndSetSemantics {
                contentDescription = "Slimy by hyprnode"
                heading()
            },
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("slimy", style = Theme.voice(34.sp), color = Theme.ink, maxLines = 1)
            Text("by hyprnode", style = Theme.voice(18.sp), color = Color(Brand.hypr), maxLines = 1,
                modifier = Modifier.padding(bottom = 4.dp))
        }
        Text(
            "A tiny slime that lives in your notifications.",
            style = MaterialTheme.typography.bodyMedium,
            color = Theme.inkSoft,
            textAlign = TextAlign.Center,
        )
    }
}

/** The app icon drawn from the sprites: the young lime slime on its dark floor, laid out like `tools/make_icon.py`. */
@Composable
private fun AboutAppIcon(size: Dp) {
    val unit = size / 1024
    val frames = SlimeSprites.frames(Stage.young, SpriteAnim.idle)
    val anchors = SlimeSprites.anchors(Stage.young, SpriteAnim.idle)
    val shape = RoundedCornerShape(size * 0.2237f)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(Color(Brand.bg))
            .border(0.5.dp, Theme.separator, shape)
            .clearAndSetSemantics { },
    ) {
        Box(
            Modifier
                .offset(y = unit * 874)
                .size(width = size, height = unit * 24)
                .background(Color(Brand.line)),
        )
        PixelSlime(
            frame = frames.firstOrNull() ?: SlimeSprites.blank,
            skin = Skin.lime,
            stage = Stage.young,
            anchor = anchors.firstOrNull(),
            modifier = Modifier
                .offset(x = unit * 80, y = unit * 154)
                .size(width = unit * 864, height = unit * 720),
        )
    }
}

/** This build's version name and number. */
object AppVersion {
    fun of(context: Context): Pair<String, String> = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        (info.versionName ?: "1.1") to PackageInfoCompat.getLongVersionCode(info).toString()
    }.getOrDefault("1.1" to "1")

    /** The marketing version without the patch number: "1.1" for 1.1.2. */
    fun marketing(full: String): String = full.split(".").take(2).joinToString(".")
}

/** The full license of Pixelify Sans, which it asks to ship with the font. */
@Composable
fun LicenseScreen(onBack: () -> Unit) {
    PushedScaffold(title = "Pixelify Sans", onBack = onBack) { padding ->
        ScreenColumn(padding, spacing = 12.dp) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (block in PixelifyLicense.blocks) {
                        when (block) {
                            is PixelifyLicense.Block.heading -> Text(
                                block.text,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Theme.ink,
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .semantics { heading() },
                            )
                            is PixelifyLicense.Block.text -> Text(block.text, style = MaterialTheme.typography.bodySmall, color = Theme.ink)
                        }
                    }
                }
            }
        }
    }
}

object PixelifyLicense {
    sealed class Block {
        data class heading(val text: String) : Block()
        data class text(val text: String) : Block()
    }

    val blocks: List<Block> = listOf(
        Block.text("Copyright 2021 The Pixelify Sans Project Authors (https://github.com/eifetx/Pixelify-Sans)"),
        Block.text("This Font Software is licensed under the SIL Open Font License, Version 1.1. This license is copied below, and is also available with a FAQ at: https://scripts.sil.org/OFL"),
        Block.heading("SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007"),
        Block.heading("PREAMBLE"),
        Block.text("The goals of the Open Font License (OFL) are to stimulate worldwide development of collaborative font projects, to support the font creation efforts of academic and linguistic communities, and to provide a free and open framework in which fonts may be shared and improved in partnership with others."),
        Block.text("The OFL allows the licensed fonts to be used, studied, modified and redistributed freely as long as they are not sold by themselves. The fonts, including any derivative works, can be bundled, embedded, redistributed and/or sold with any software provided that any reserved names are not used by derivative works. The fonts and derivatives, however, cannot be released under any other type of license. The requirement for fonts to remain under this license does not apply to any document created using the fonts or their derivatives."),
        Block.heading("DEFINITIONS"),
        Block.text("\"Font Software\" refers to the set of files released by the Copyright Holder(s) under this license and clearly marked as such. This may include source files, build scripts and documentation."),
        Block.text("\"Reserved Font Name\" refers to any names specified as such after the copyright statement(s)."),
        Block.text("\"Original Version\" refers to the collection of Font Software components as distributed by the Copyright Holder(s)."),
        Block.text("\"Modified Version\" refers to any derivative made by adding to, deleting, or substituting -- in part or in whole -- any of the components of the Original Version, by changing formats or by porting the Font Software to a new environment."),
        Block.text("\"Author\" refers to any designer, engineer, programmer, technical writer or other person who contributed to the Font Software."),
        Block.heading("PERMISSION & CONDITIONS"),
        Block.text("Permission is hereby granted, free of charge, to any person obtaining a copy of the Font Software, to use, study, copy, merge, embed, modify, redistribute, and sell modified and unmodified copies of the Font Software, subject to the following conditions:"),
        Block.text("1) Neither the Font Software nor any of its individual components, in Original or Modified Versions, may be sold by itself."),
        Block.text("2) Original or Modified Versions of the Font Software may be bundled, redistributed and/or sold with any software, provided that each copy contains the above copyright notice and this license. These can be included either as stand-alone text files, human-readable headers or in the appropriate machine-readable metadata fields within text or binary files as long as those fields can be easily viewed by the user."),
        Block.text("3) No Modified Version of the Font Software may use the Reserved Font Name(s) unless explicit written permission is granted by the corresponding Copyright Holder. This restriction only applies to the primary font name as presented to the users."),
        Block.text("4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font Software shall not be used to promote, endorse or advertise any Modified Version, except to acknowledge the contribution(s) of the Copyright Holder(s) and the Author(s) or with their explicit written permission."),
        Block.text("5) The Font Software, modified or unmodified, in part or in whole, must be distributed entirely under this license, and must not be distributed under any other license. The requirement for fonts to remain under this license does not apply to any document created using the Font Software."),
        Block.heading("TERMINATION"),
        Block.text("This license becomes null and void if any of the above conditions are not met."),
        Block.heading("DISCLAIMER"),
        Block.text("THE FONT SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM OTHER DEALINGS IN THE FONT SOFTWARE."),
    )
}
