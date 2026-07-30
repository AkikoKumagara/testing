/***************************************************************************
 * Y2/Android key map for the Rockbox SDL simulator.
 *
 * Copyright (C) 2026
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 ****************************************************************************/

#include <SDL.h>
#include "button.h"
#include "buttonmap.h"

/*
 * The keyboard layout mirrors the Y2 input events captured on real hardware:
 *
 *   Up/Down       wheel anticlockwise/clockwise
 *   Left/Right    previous/next
 *   Enter         centre
 *   Escape        back
 *   M             menu
 *   Space/P       play/pause
 *   Page Up/Down  volume up/down
 *   [ / ]         media previous/next
 */
int key_to_button(int keyboard_button)
{
    switch (keyboard_button)
    {
        case SDLK_UP:
        case SDLK_KP_8:
            return BUTTON_DPAD_UP;
        case SDLK_DOWN:
        case SDLK_KP_2:
            return BUTTON_DPAD_DOWN;
        case SDLK_LEFT:
        case SDLK_KP_4:
            return BUTTON_DPAD_LEFT;
        case SDLK_RIGHT:
        case SDLK_KP_6:
            return BUTTON_DPAD_RIGHT;
        case SDLK_RETURN:
        case SDLK_KP_ENTER:
        case SDLK_KP_5:
            return BUTTON_DPAD_CENTER;
        case SDLK_ESCAPE:
        case SDLK_BACKSPACE:
            return BUTTON_BACK;
        case SDLK_m:
            return BUTTON_MENU;
        case SDLK_SPACE:
        case SDLK_p:
            return BUTTON_MULTIMEDIA_PLAYPAUSE;
        case SDLK_PAGEUP:
        case SDLK_KP_PLUS:
            return BUTTON_VOL_UP;
        case SDLK_PAGEDOWN:
        case SDLK_KP_MINUS:
            return BUTTON_VOL_DOWN;
        case SDLK_LEFTBRACKET:
            return BUTTON_MEDIA_PREV;
        case SDLK_RIGHTBRACKET:
            return BUTTON_MEDIA_NEXT;
        default:
            return BUTTON_NONE;
    }
}

/*
 * Click targets are intentionally placed around the edge of the 480x360
 * framebuffer so --mapping and --debugbuttons are useful without a device
 * bitmap. Touchscreen input remains available over the full framebuffer.
 */
struct button_map bm[] = {
    { SDLK_UP,           240,  24, 20, "Wheel anticlockwise / Up" },
    { SDLK_DOWN,         240, 336, 20, "Wheel clockwise / Down" },
    { SDLK_LEFT,          24, 180, 20, "Previous / Left" },
    { SDLK_RIGHT,        456, 180, 20, "Next / Right" },
    { SDLK_RETURN,       240, 180, 24, "Centre" },
    { SDLK_ESCAPE,        48, 336, 20, "Back" },
    { SDLK_m,            432, 336, 20, "Menu" },
    { SDLK_SPACE,        240, 300, 20, "Play / Pause" },
    { SDLK_PAGEUP,        48,  24, 20, "Volume up" },
    { SDLK_PAGEDOWN,     432,  24, 20, "Volume down" },
    { 0,                   0,   0,  0, "None" }
};
