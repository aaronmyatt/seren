(ns seren.app.server
  "Ring HTTP server with Reitit routing.
   Serves Hiccup-rendered HTML pages and static assets from public/.

   Start in REPL:  (seren.app.server/start! {:port 3000})
   Stop:           (seren.app.server/stop!)

   See: https://github.com/metosin/reitit#ring-router
   See: https://github.com/ring-clojure/ring/wiki/Static-Resources"
  (:require [reitit.ring :as reitit-ring]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [ring.util.response :as response]
            [seren.app.pages.dashboard :as dashboard-page]))

;; --- Routes ---

(def routes
  "Reitit route data. Each route maps to a Ring handler.
   See: https://cljdoc.org/d/metosin/reitit/CURRENT/doc/ring/ring-router"
  [["/" {:get {:handler (fn [_] (response/redirect "/dashboard"))}}]
   ["/dashboard" {:get {:handler dashboard-page/handler}}]])

;; --- Handler ---

(defn app-handler
  "Builds the Ring handler stack:
   1. Reitit router matches routes to page handlers
   2. wrap-file serves static assets (CSS, JS) from public/
   3. wrap-content-type sets Content-Type from file extensions
   4. wrap-not-modified adds 304 responses for unchanged assets"
  []
  (-> (reitit-ring/ring-handler
        (reitit-ring/router routes)
        (reitit-ring/create-default-handler))
      (wrap-file "public")
      wrap-content-type
      wrap-not-modified))

;; --- Server lifecycle ---

(defonce ^:private server-atom (atom nil))

(defn start!
  "Starts Jetty on the given port. Returns the server instance.
   Default port: 3000."
  [{:keys [port] :or {port 3000}}]
  (when @server-atom
    (println "Server already running. Call (stop!) first.")
    (throw (ex-info "Server already running" {:port port})))
  (let [srv (jetty/run-jetty (app-handler) {:port port :join? false})]
    (reset! server-atom srv)
    (println (str "Seren server started on http://localhost:" port))
    srv))

(defn stop!
  "Stops the running Jetty server."
  []
  (when-let [srv @server-atom]
    (.stop srv)
    (reset! server-atom nil)
    (println "Seren server stopped.")))

(defn -main
  "Entry point for running the server standalone.
   Blocks the main thread until interrupted."
  [& _args]
  (start! {:port 3000})
  @(promise))

(comment
  ;; Start/stop from REPL
  (start! {:port 3000})
  (stop!)

  ;; Test handler directly
  ((app-handler) {:request-method :get :uri "/dashboard"}))
