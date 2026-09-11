package com.vasiliastyper.model

  import android.graphics.Typeface

  enum class TextEffect {
      NONE, OUTLINE, SHADOW, OUTLINE_SHADOW, GRADIENT, TEXTURE, WARP
  }

  enum class TextAlign {
      LEFT, CENTER, RIGHT
  }

  data class TextElement(
      val id: String = java.util.UUID.randomUUID().toString(),
      /** User-facing layer metadata shared with pixel and image layers. */
      var name: String = "Text Layer",
      var isVisible: Boolean = true,
      var isLocked: Boolean = false,
      var blendMode: String = "Normal",
      var text: String = "",
      var x: Float = 0f,
      var y: Float = 0f,
      var width: Float = 300f,
      var height: Float = 100f,
      var fontSize: Float = 36f,
      var fontName: String = "default",
      var typeface: Typeface? = null,
      var color: Int = android.graphics.Color.WHITE,
      var isBold: Boolean = false,
      var isItalic: Boolean = false,
      var effect: TextEffect = TextEffect.NONE,

      var outlineColor: Int   = android.graphics.Color.BLACK,
      var outlineWidth: Float = 4f,
      var outlineOpacity: Int = 100,

      var shadowDx: Float     = 4f,
      var shadowDy: Float     = 4f,
      var shadowRadius: Float = 6f,
      /** Solid shadow expansion; 0 keeps legacy rendering. */
      var shadowSpread: Float = 0f,
      var shadowColor: Int    = android.graphics.Color.BLACK,
      var shadowOpacity: Int  = 80,

      var gradientStartColor: Int = android.graphics.Color.WHITE,
      var gradientEndColor: Int   = android.graphics.Color.GRAY,
      /** Ordered fill-gradient stops. Empty lists fall back to start/end colors. */
      var gradientColors: MutableList<Int> = mutableListOf(),
      /** Direction in degrees: 0 = left→right, 90 = top→bottom. */
      var gradientAngle: Float = 90f,

      var align: TextAlign = TextAlign.CENTER,
      var rotation: Float  = 0f,

      var layerId: String? = null,

      var textureUri: String? = null,
      var spans: MutableList<TextSpan>? = null,
      var perspCorners: FloatArray? = null,

      // Mesh form control points: flat array [x0,y0, x1,y1, ...] representing
      // the grid vertices in local element coordinates. When non-null, text is
      // warped through a mesh instead of following a line path.
      var meshPoints: FloatArray? = null,
      var meshCols: Int = 3,
      var meshRows: Int = 3,

      // v5.2 — Opacity + blur
      var opacity: Int = 100,
      var blurType: String = "NONE",
      var blurRadius: Float = 0f,
      var blurMotionAngle: Float = 0f,
      var blurMotionDistance: Float = 20f,

      // v5.3 — Leading & Justify
      var leading: Float = 120f,
      /** Extra spacing between glyphs in canvas pixels. */
      var tracking: Float = 0f,
      var justify: Boolean = false,

      // Text path deformation. Modes: NONE, CURVE_UP, CURVE_DOWN, WAVE, ARCH, VALLEY.
      // Amount is a signed percentage (-100..100); waveCycles controls repetition.
      var textPathMode: String = "NONE",
      var textPathAmount: Float = 35f,
      var textPathCycles: Float = 1.5f,

      // Stackable effects: every visual layer can be enabled independently.
      var enableOutline: Boolean = false,
      var enableShadow: Boolean = false,
      var enableGradient: Boolean = false,
      var enableTexture: Boolean = false,
      // v8.0 — text transform (NONE | UPPER | LOWER | TITLE)
      var textTransform: String = "NONE",
      // v8.0 — outline gradient
      var enableOutlineGradient: Boolean = false,
      var outlineGradStartColor: Int = android.graphics.Color.BLACK,
      var outlineGradEndColor: Int = android.graphics.Color.GRAY
  )
  