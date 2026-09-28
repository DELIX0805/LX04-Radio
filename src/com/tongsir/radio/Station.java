package com.tongsir.radio;

/** 电台数据模型。 */
public class Station {

    public String id;
    public String name;
    public String group;
    public String url;
    /** "HLS" / "MP3"，只作展示用 */
    public String codec = "";
    /** 说明文字，会显示在主界面副行（该行拿不到 ICY 曲目时的替代内容） */
    public String desc = "";
    /** 是否已在 LX04 真机实测通过 */
    public boolean verified = false;

    public Station() {}

    public Station(String id, String name, String group, String url) {
        this.id = id;
        this.name = name;
        this.group = group;
        this.url = url;
    }

    @Override
    public String toString() { return name; }
}
