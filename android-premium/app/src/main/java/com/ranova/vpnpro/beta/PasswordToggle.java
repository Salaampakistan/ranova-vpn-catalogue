package com.ranova.vpnpro.beta;

import android.text.InputType;
import android.view.MotionEvent;
import android.widget.EditText;

final class PasswordToggle {
    private PasswordToggle(){}

    static void attach(EditText field){
        field.setInputType(
                InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        field.setCompoundDrawablePadding(Ui.dp(field.getContext(),8));
        setIcon(field,false);

        field.setOnTouchListener((v,event)->{
            if(event.getAction()!=MotionEvent.ACTION_UP) return false;

            android.graphics.drawable.Drawable right=
                    field.getCompoundDrawablesRelative()[2];
            if(right==null) return false;

            float touchStart=field.getWidth()
                    -field.getPaddingEnd()
                    -right.getBounds().width()
                    -Ui.dp(field.getContext(),12);

            if(event.getX()<touchStart) return false;

            int pos=Math.max(0,field.getSelectionStart());
            boolean visible=(field.getInputType()
                    & InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                    ==InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;

            if(visible){
                field.setInputType(
                        InputType.TYPE_CLASS_TEXT |
                        InputType.TYPE_TEXT_VARIATION_PASSWORD
                );
            }else{
                field.setInputType(
                        InputType.TYPE_CLASS_TEXT |
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                );
            }

            setIcon(field,!visible);
            field.setSelection(Math.min(pos,field.length()));
            field.performClick();
            return true;
        });
    }

    private static void setIcon(EditText field,boolean visible){
        field.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0,
                0,
                visible ? R.drawable.ic_eye_off : R.drawable.ic_eye,
                0
        );
    }
}
