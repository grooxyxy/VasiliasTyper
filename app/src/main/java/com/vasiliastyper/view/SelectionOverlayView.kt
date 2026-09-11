package com.vasiliastyper.view
  import android.content.Context;import android.graphics.*;import android.util.AttributeSet
  import android.view.MotionEvent;import android.view.View;import com.vasiliastyper.model.OcrSelectionRect
  class SelectionOverlayView @JvmOverloads constructor(ctx:Context,attrs:AttributeSet?=null):View(ctx,attrs){
      interface Listener{fun onChanged(rects:List<OcrSelectionRect>)}
      private val bp=Paint().apply{color=Color.parseColor("#FF4081");style=Paint.Style.STROKE;strokeWidth=3f;isAntiAlias=true}
      private val fp=Paint().apply{color=Color.parseColor("#33FF4081");style=Paint.Style.FILL}
      private val tp=Paint().apply{color=Color.WHITE;textSize=30f;isAntiAlias=true}
      private val lp=Paint().apply{color=Color.parseColor("#CC1565C0");style=Paint.Style.FILL}
      val selections=mutableListOf<OcrSelectionRect>();private var curR:RectF?=null;private var ox=0f;private var oy=0f
      var selectionEnabled=false;var imageBounds=RectF();var imgW=1f;var imgH=1f;var listener:Listener?=null
      fun clearAll(){selections.clear();curR=null;invalidate()}
      fun undoLast(){if(selections.isNotEmpty()){selections.removeAt(selections.lastIndex);invalidate()}}
      override fun onTouchEvent(e:MotionEvent):Boolean{
          if(!selectionEnabled)return false
          when(e.action){
              MotionEvent.ACTION_DOWN->{ox=e.x;oy=e.y;curR=RectF(e.x,e.y,e.x,e.y)}
              MotionEvent.ACTION_MOVE->{curR?.set(minOf(ox,e.x),minOf(oy,e.y),maxOf(ox,e.x),maxOf(oy,e.y));invalidate()}
              MotionEvent.ACTION_UP->{val r=curR?:return true;if(r.width()>20&&r.height()>20){selections.add(OcrSelectionRect(selections.size,RectF(r),s2i(r)));listener?.onChanged(selections)};curR=null;invalidate()}
          }
          return true
      }
      private fun s2i(sr:RectF):RectF{if(imageBounds.width()==0f)return sr;val sx=imgW/imageBounds.width();val sy=imgH/imageBounds.height();return RectF((sr.left-imageBounds.left)*sx,(sr.top-imageBounds.top)*sy,(sr.right-imageBounds.left)*sx,(sr.bottom-imageBounds.top)*sy)}
      override fun onDraw(c:Canvas){super.onDraw(c);for((i,sel) in selections.withIndex()){c.drawRect(sel.screenRect,fp);c.drawRect(sel.screenRect,bp);val lbl="${i+1}";val lx=sel.screenRect.left+4;val ly=sel.screenRect.top+32;c.drawRoundRect(lx-2,ly-26,lx+tp.measureText(lbl)+6,ly+6,4f,4f,lp);c.drawText(lbl,lx,ly,tp)};curR?.let{c.drawRect(it,fp);c.drawRect(it,bp)}}
  }