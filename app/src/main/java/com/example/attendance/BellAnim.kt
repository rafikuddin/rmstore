package com.example.attendance

import android.animation.AnimatorSet
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * The notification bell: it swings like a ringing bell (hanging from its top, the swings getting smaller),
 * then the red count badge pops in with a small bounce. Called when a new notice arrives and when Home opens with unread notices.
 */
object BellAnim {
    fun ring(bell: View, badge: View?) {
        (bell.tag as? AnimatorSet)?.cancel()
        bell.pivotX = bell.width / 2f
        bell.pivotY = bell.height * 0.12f
        val swing = ObjectAnimator.ofPropertyValuesHolder(
            bell,
            PropertyValuesHolder.ofKeyframe(
                View.ROTATION,
                Keyframe.ofFloat(0f, 0f), Keyframe.ofFloat(0.12f, -18f), Keyframe.ofFloat(0.30f, 16f), Keyframe.ofFloat(0.46f, -11f),
                Keyframe.ofFloat(0.62f, 8f), Keyframe.ofFloat(0.77f, -4f), Keyframe.ofFloat(0.90f, 2f), Keyframe.ofFloat(1f, 0f)
            )
        ).apply { duration = 900; interpolator = AccelerateDecelerateInterpolator() }
        val set = AnimatorSet()
        if (badge != null && badge.visibility == View.VISIBLE) {
            badge.scaleX = 0f; badge.scaleY = 0f
            val pop = AnimatorSet().apply {
                playTogether(ObjectAnimator.ofFloat(badge, View.SCALE_X, 0f, 1f), ObjectAnimator.ofFloat(badge, View.SCALE_Y, 0f, 1f))
                duration = 380; interpolator = OvershootInterpolator(3f)
            }
            set.playSequentially(swing, pop)
            set.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: android.animation.Animator) { badge.scaleX = 1f; badge.scaleY = 1f; bell.rotation = 0f }
            })
        } else set.play(swing)
        bell.tag = set
        set.start()
    }
}
