package com.example.myapplication

import android.view.WindowInsets
import android.view.WindowInsetsController
import com.google.androidgamesdk.GameActivity

class MainActivity : GameActivity() {
  companion object {
    init {
      System.loadLibrary("myapplication")
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      hideSystemUi()
    }
  }

  private fun hideSystemUi() {
    window.insetsController?.apply {
      hide(WindowInsets.Type.systemBars())
      systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
  }
}
