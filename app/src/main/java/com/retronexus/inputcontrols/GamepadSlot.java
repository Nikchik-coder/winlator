package com.retronexus.inputcontrols;

public interface GamepadSlot {
    String getName();

    GamepadState getGamepadState();

    GamepadVibration getGamepadVibration();
}
