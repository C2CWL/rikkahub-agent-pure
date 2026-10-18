package me.rerere.rikkahub.ui.components.ui

import android.graphics.drawable.AnimatedVectorDrawable
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.LoadingIndicatorStyle
import me.rerere.rikkahub.data.datastore.effectiveLoadingIndicatorStyle
import me.rerere.rikkahub.ui.context.LocalSettings

@Composable
fun RabbitLoadingIndicator(modifier: Modifier = Modifier) {
    val style = LocalSettings.current.displaySetting.effectiveLoadingIndicatorStyle
    val primaryColor = MaterialTheme.colorScheme.primary.toArgb()

    if (style == LoadingIndicatorStyle.SPARKLE) {
        SparkleLoadingIndicator(modifier = modifier)
    } else if (style == LoadingIndicatorStyle.SHIMMER) {
        ShimmerBar(modifier = modifier)
    } else if (style == LoadingIndicatorStyle.APP_ICON) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                ImageView(context).apply {
                    val drawable = AppCompatResources.getDrawable(context, R.drawable.rabbit) as? AnimatedVectorDrawable
                    setImageDrawable(drawable)
                    drawable?.setTint(primaryColor)
                    drawable?.start()
                }
            },
            update = { imageView ->
                (imageView.drawable as? AnimatedVectorDrawable)?.apply {
                    setTint(primaryColor)
                    start()
                }
            }
        )
    } else {
        ContainedLoadingIndicator(
            modifier = modifier,
        )
    }
}
