(ns blzr.engine.patch.serial
  "Transit+JSON serialization for patch descriptors.
   Preserves ClojureScript keywords, maps, and vectors through round-trip."
  (:require [cognitect.transit :as t]))

(def ^:private writer (t/writer :json))
(def ^:private reader (t/reader :json))

(defn encode
  "Patch descriptor -> Transit JSON string."
  [descriptor]
  (t/write writer descriptor))

(defn decode
  "Transit JSON string -> patch descriptor."
  [s]
  (t/read reader s))
