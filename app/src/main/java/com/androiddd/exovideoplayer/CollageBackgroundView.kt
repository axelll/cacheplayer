package com.androiddd.exovideoplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.random.Random

/**
 * View для отображения коллажа из одного изображения
 * Основное изображение размещается по центру, а его копии - по краям в разных размерах и поворотах
 */
class CollageBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var mainBitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = Random(System.currentTimeMillis())
    private val matrix = Matrix()

    // Количество копий изображения по краям
    private val numCopies = 12

    // Массивы для хранения параметров каждой копии
    private val scales = FloatArray(numCopies)
    private val rotations = FloatArray(numCopies)
    private val positionsX = FloatArray(numCopies)
    private val positionsY = FloatArray(numCopies)

    init {
        // Загружаем изображение из ресурсов
        val drawable = ContextCompat.getDrawable(context, R.drawable.background)
        if (drawable is BitmapDrawable) {
            mainBitmap = drawable.bitmap
        }

        // Генерируем случайные параметры для каждой копии
        for (i in 0 until numCopies) {
            scales[i] = random.nextFloat() * 0.5f + 0.3f // от 0.3 до 0.8 от оригинала
            rotations[i] = random.nextFloat() * 40f - 20f // от -20 до +20 градусов
            positionsX[i] = random.nextFloat()
            positionsY[i] = random.nextFloat()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Рисуем темный фон
        canvas.drawARGB(255, 5, 10, 20) // Более темный синий фон

        mainBitmap?.let { bitmap ->
            val centerX = width / 2f
            val centerY = height / 2f

            // Размер основного изображения (по центру)
            val mainScale = 0.5f // 50% от ширины экрана
            val mainWidth = width * mainScale
            val mainHeight = mainWidth * bitmap.height / bitmap.width

            // Рисуем копии по краям
            for (i in 0 until numCopies) {
                matrix.reset()

                // Определяем позицию копии (по краям экрана)
                val posX = if (positionsX[i] < 0.5f) {
                    width * positionsX[i] * 0.5f // Левая половина
                } else {
                    width * (0.5f + positionsX[i] * 0.5f) // Правая половина
                }

                val posY = if (positionsY[i] < 0.33f) {
                    height * positionsY[i] * 0.3f // Верхняя треть
                } else if (positionsY[i] < 0.66f) {
                    height * (0.35f + positionsY[i] * 0.3f) // Средняя треть
                } else {
                    height * (0.7f + positionsY[i] * 0.3f) // Нижняя треть
                }

                // Размер копии
                val copyWidth = mainWidth * scales[i]
                val copyHeight = mainHeight * scales[i]

                // Настраиваем матрицу трансформации
                matrix.postScale(
                    copyWidth / bitmap.width,
                    copyHeight / bitmap.height
                )
                matrix.postRotate(rotations[i], copyWidth / 2, copyHeight / 2)
                matrix.postTranslate(
                    posX - copyWidth / 2,
                    posY - copyHeight / 2
                )

                // Рисуем копию с полупрозрачностью
                paint.alpha = (50 + random.nextInt(70)).coerceAtMost(120)
                canvas.drawBitmap(bitmap, matrix, paint)
            }

            // Рисуем основное изображение по центру (поверх всех копий)
            matrix.reset()
            matrix.postScale(
                mainWidth / bitmap.width,
                mainHeight / bitmap.height
            )
            matrix.postTranslate(
                centerX - mainWidth / 2,
                centerY - mainHeight / 2
            )

            paint.alpha = 180 // Основное изображение с небольшой прозрачностью
            canvas.drawBitmap(bitmap, matrix, paint)
        }
    }
}
