@file:Suppress("UnusedReceiverParameter")

package lava.ui.component

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import lava.ui.R

@StringRes
fun Throwable?.getErrorTitleRes(): Int = R.string.error_title

fun Throwable?.getStringRes(): Int = R.string.error_something_goes_wrong

@DrawableRes
fun Throwable?.getIllRes(): Int = R.drawable.ill_error
