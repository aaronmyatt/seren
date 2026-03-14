(ns seren.app.islands.shared
  "Shared CLJS island module — loaded on every page.
   Provides DOM helpers for the island pattern.

   Island pattern: server-rendered HTML has data-island attributes.
   Island modules use find-island to locate mount points and attach behavior.
   See: https://jasonformat.com/islands-architecture/"
  (:require ["pocketbase" :default PocketBase]))

;; --- PocketBase client ---
;; PocketBase is used for persisting content, reviews, and scores.
;; See: https://github.com/pocketbase/js-sdk

(defonce ^:private pb-client (atom nil))

(def ^:private pb-url
  "PocketBase server URL. In development, PocketBase runs on port 8090.
   See: https://pocketbase.io/docs/"
  "http://127.0.0.1:8090")

(defn init-pb!
  "Initializes the PocketBase client singleton."
  [url]
  (reset! pb-client (PocketBase. url)))

(defn pb
  "Returns the PocketBase client instance."
  []
  @pb-client)

;; --- Island DOM helpers ---

(defn find-island
  "Finds the first DOM element with [data-island=name].
   Returns the element or nil if not found."
  [island-name]
  (.querySelector js/document
    (str "[data-island=\"" island-name "\"]")))

(defn find-islands
  "Finds all DOM elements with [data-island=name].
   Returns a seq of DOM elements."
  [island-name]
  (array-seq (.querySelectorAll js/document
               (str "[data-island=\"" island-name "\"]"))))

;; --- Lifecycle ---

(defn ^:export init
  "Called on page load. Initializes PocketBase client."
  []
  (init-pb! pb-url)
  (js/console.log "[seren] PocketBase client initialized"))

(defn on-reload
  "Called by shadow-cljs after hot reload."
  []
  (js/console.log "[seren] hot reload"))

;; Initialize on first load
(init)
