package com.tongsir.radio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** 从 res/raw/stations.json 读电台清单。 */
public class StationRepo {

    public static List<Station> load(Context ctx) {
        List<Station> out = new ArrayList<Station>();
        InputStream in = null;
        try {
            in = ctx.getResources().openRawResource(R.raw.stations);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            String txt = new String(bo.toByteArray(), "UTF-8");

            JSONObject root = new JSONObject(txt);
            JSONArray arr = root.getJSONArray("stations");
            // 收藏以 id 为键，id 重复会让"收藏一台 = 点亮多台"，所以这里兜底去重：
            // 重复的保留电台本身（不能丢数据），只把 id 改成确定性后缀，保证唯一。
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Station s = new Station();
                s.id = o.optString("id", "s" + i);
                s.name = o.optString("name", "");
                s.group = o.optString("group", "");
                s.url = o.optString("url", "");
                s.codec = o.optString("codec", "");
                s.desc = o.optString("desc", "");
                s.verified = o.optBoolean("verified", false);
                if (s.url.length() == 0 || s.name.length() == 0) continue;
                if (!seen.add(s.id)) {
                    String base = s.id;
                    int k = 2;
                    while (!seen.add(base + "#" + k)) k++;
                    s.id = base + "#" + k;
                    android.util.Log.w("StationRepo",
                            "重复电台 id: " + base + "（" + s.name + "）已改为 " + s.id);
                }
                out.add(s);
            }
        } catch (Throwable t) {
            android.util.Log.e("StationRepo", "读 stations.json 失败: " + t);
        } finally {
            try { if (in != null) in.close(); }
            catch (Throwable t) { android.util.Log.d("StationRepo", "close(): " + t); }
        }
        return out;
    }
}
