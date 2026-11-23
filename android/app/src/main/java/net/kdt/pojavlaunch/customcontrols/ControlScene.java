package net.kdt.pojavlaunch.customcontrols;

public enum ControlScene {
    ALL, GAMEPLAY, MENU;

    public boolean shows(boolean inGame, boolean inMenu) {
        return this == ALL || (this == GAMEPLAY ? inGame : inMenu);
    }
}
