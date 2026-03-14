(ns seren.app.islands.dashboard
  "Dashboard page island — the interactive layer for the Seren dashboard.

   Will be extended to show upcoming reviews, content feed, and score history
   as Core and Adapter modules are implemented.

   See: https://jasonformat.com/islands-architecture/"
  (:require [seren.app.islands.shared :as shared]))

(defn ^:export init
  "Mounts the dashboard island."
  []
  (js/console.log "[seren] dashboard island mounted"))

;; Initialize on load
(init)
