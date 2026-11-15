package com.leaguetool.data.ddragon;

import com.leaguetool.cache.FileCache;

/**
 * Verweis auf ein Data-Dragon-Bild inkl. Cache-Ort.
 *
 * @param url        Download-URL
 * @param cacheScope Cache-Scope (Patch oder {@link FileCache#GLOBAL})
 * @param cacheKey   Cache-Schlüssel
 */
public record ImageRef(String url, String cacheScope, String cacheKey) {

    public static ImageRef champion(String patch, String file) {
        return patchImage(patch, "champion", file);
    }

    public static ImageRef item(String patch, String file) {
        return patchImage(patch, "item", file);
    }

    /** Champion-Fähigkeit oder Beschwörerzauber. */
    public static ImageRef spell(String patch, String file) {
        return patchImage(patch, "spell", file);
    }

    public static ImageRef passive(String patch, String file) {
        return patchImage(patch, "passive", file);
    }

    /** Runen- und Runenbaum-Icons sind patch-unabhängig ({@code /cdn/img/<icon>}). */
    public static ImageRef rune(String icon) {
        return new ImageRef(DDragonClient.BASE_URL + "/cdn/img/" + icon, FileCache.GLOBAL, "img/" + icon);
    }

    private static ImageRef patchImage(String patch, String group, String file) {
        return new ImageRef(DDragonClient.BASE_URL + "/cdn/" + patch + "/img/" + group + "/" + file,
                patch, "img/" + group + "/" + file);
    }
}
