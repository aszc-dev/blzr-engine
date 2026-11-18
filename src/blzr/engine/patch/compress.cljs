(ns blzr.engine.patch.compress
  "LZString compression for patch descriptors.
   Produces URL-safe encoded strings for fragment hash sharing."
  (:require ["lz-string" :as LZString]
            [blzr.engine.patch.serial :as serial]))

(defn compress
  "Descriptor -> compressed URL-safe string."
  [descriptor]
  (.compressToEncodedURIComponent LZString (serial/encode descriptor)))

(defn decompress
  "Compressed string -> descriptor."
  [s]
  (serial/decode (.decompressFromEncodedURIComponent LZString s)))
