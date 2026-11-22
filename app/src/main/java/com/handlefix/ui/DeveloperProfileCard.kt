package com.handlefix.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.handlefix.all.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val RAIN_RYAN_PROFILE_URL = "https://www.coolapk.com/u/4388765"

/**
 * “模块设置与调试”页底部的开发者信息卡。
 *
 * 头像使用原图并在 Compose 层做居中圆形裁切，避免维护额外的裁剪副本；
 * 点击整张卡片交由系统处理 HTTPS 链接，可直接唤起酷安或浏览器。
 */
@Composable
fun DeveloperProfileCard() {
    val context = LocalContext.current
    val view = LocalView.current

    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    UiUtil.hapticClick(view)
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(RAIN_RYAN_PROFILE_URL)),
                        )
                    } catch (_: Throwable) {
                        UiUtil.toast(context, "未找到可打开酷安主页的应用", true)
                    }
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.avatar_rain_ryan),
                contentDescription = "Rain_Ryan 头像",
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Rain_Ryan",
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "本模块由DeepSeek x ChatGPT联合研发（Vibe Coding）",
                    modifier = Modifier.padding(top = 3.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "›",
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                fontSize = 32.sp,
            )
        }
    }
}
