(ns seren.app.server
  "Ring HTTP server with Reitit routing.
   Serves Hiccup-rendered HTML pages, an EDN API, and static assets.

   Routes:
     GET  /                          → redirect to /library
     GET  /library                   → content library (ingest + browse)
     GET  /dashboard                 → dashboard (upcoming reviews, scores)
     GET  /review/:id                → review session page (free recall)
     POST /api/ingest                → ingest content (EDN body)
     GET  /api/content               → list all content (EDN response)
     GET  /api/reviews               → list all reviews (EDN response)
     GET  /api/reviews/due           → list reviews due now (EDN response)
     GET  /api/reviews/:id/session   → review session data (review + content + scaffold)
     POST /api/reviews/:id/complete  → complete a review with quality score

   Start in REPL:  (seren.app.server/start! {:port 3000})
   Stop:           (seren.app.server/stop!)

   See: https://github.com/metosin/reitit#ring-router
   See: https://github.com/ring-clojure/ring/wiki"
  (:require [reitit.ring :as reitit-ring]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [ring.util.response :as response]
            [clojure.edn :as edn]
            [seren.app.main :as app]
            [seren.app.pages.library :as library-page]
            [seren.app.pages.dashboard :as dashboard-page]
            [seren.app.pages.review :as review-page]))

;; --- App state ---
;; Store directories are configurable; default to .seren-data/ in the project root.

(def ^:private default-store-dir ".seren-data/content")
(def ^:private default-review-dir ".seren-data/reviews")

(defn- app-config []
  {:store-dir  default-store-dir
   :review-dir default-review-dir})

;; --- EDN API helpers ---
;; The API speaks EDN — natural for ClojureScript frontends.
;; A JSON layer can be added later for non-CLJS clients.

(defn- read-edn-body
  "Reads the request body as EDN. Returns nil on parse failure.
   See: https://clojure.org/reference/reader#_edn"
  [request]
  (try
    (when-let [body (:body request)]
      (let [s (slurp body)]
        (when-not (empty? s)
          (edn/read-string s))))
    (catch Exception _ nil)))

(defn- edn-response
  "Creates a Ring response with EDN content type."
  [status body]
  (-> (response/response (pr-str body))
      (response/status status)
      (response/content-type "application/edn")))

;; --- API handlers ---

(defn- ingest-handler
  "POST /api/ingest — ingests content from EDN body.
   Expects: {:text \"...\" :title \"...\" :url \"...\"}
   Returns: {:success true :content {...} :review {...}} or {:success false :reason \"...\"}"
  [request]
  (if-let [body (read-edn-body request)]
    (let [result (app/ingest-content!
                   (merge (app-config) body))]
      (if (:success result)
        (edn-response 201 result)
        (edn-response 400 result)))
    (edn-response 400 {:success false :reason "Could not parse request body as EDN"})))

(defn- list-content-handler
  "GET /api/content — returns all ingested content as EDN."
  [_request]
  (let [contents (app/list-all-content (app-config))]
    (edn-response 200 {:content contents})))

(defn- list-reviews-handler
  "GET /api/reviews — returns all reviews as EDN, sorted by due-at."
  [_request]
  (let [reviews (app/list-all-reviews (app-config))]
    (edn-response 200 {:reviews reviews})))

(defn- list-due-reviews-handler
  "GET /api/reviews/due — returns reviews due for practice right now."
  [_request]
  (let [reviews (app/list-due-reviews (app-config))]
    (edn-response 200 {:reviews reviews})))

(defn- review-session-handler
  "GET /api/reviews/:id/session — returns everything needed for a review session.
   Returns: {:success true :review {...} :content {...} :scaffold \"...\"}
   The scaffold is a pre-generated hint string, or nil if scaffold level is :none.

   See plan.md § 'Review Flow: Free Recall + Scaffolding'"
  [request]
  (let [review-id (get-in request [:path-params :id])
        result    (app/get-review-session (app-config) review-id)]
    (if (:success result)
      (edn-response 200 result)
      (edn-response 404 result))))

(defn- complete-review-handler
  "POST /api/reviews/:id/complete — completes a review with a quality score.
   Expects EDN body: {:quality 4}
   Returns: {:success true :completed-review {...} :next-review {...}}

   See plan.md § 'SM-2 Scheduling' for quality values (0-5)."
  [request]
  (let [review-id (get-in request [:path-params :id])]
    (if-let [body (read-edn-body request)]
      (let [quality (:quality body)]
        (if (and (integer? quality) (<= 0 quality 5))
          (let [result (app/complete-review! (app-config) review-id quality)]
            (if (:success result)
              (edn-response 200 result)
              (edn-response 404 result)))
          (edn-response 400 {:success false :reason "Quality must be an integer 0-5"})))
      (edn-response 400 {:success false :reason "Could not parse request body as EDN"}))))

;; --- Routes ---

(def routes
  "Reitit route data. Each route maps to a Ring handler.
   See: https://cljdoc.org/d/metosin/reitit/CURRENT/doc/ring/ring-router"
  [["/"                           {:get  {:handler (fn [_] (response/redirect "/library"))}}]
   ["/library"                    {:get  {:handler library-page/handler}}]
   ["/dashboard"                  {:get  {:handler dashboard-page/handler}}]
   ["/review/:id"                 {:get  {:handler review-page/handler}}]
   ["/api/ingest"                 {:post {:handler ingest-handler}}]
   ["/api/content"                {:get  {:handler list-content-handler}}]
   ["/api/reviews"                {:get  {:handler list-reviews-handler}}]
   ["/api/reviews/due"            {:get  {:handler list-due-reviews-handler}}]
   ["/api/reviews/:id/session"    {:get  {:handler review-session-handler}}]
   ["/api/reviews/:id/complete"   {:post {:handler complete-review-handler}}]])

;; --- Handler ---

(defn- wrap-exceptions
  "Ring middleware that catches unhandled exceptions, logs a full stack trace,
   and returns a 500 EDN response instead of letting Jetty swallow the error.
   See: https://ring-clojure.github.io/ring/ring.middleware.html"
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (binding [*out* *err*]
          (println (str "[seren] 500 " (:request-method request) " " (:uri request)))
          (.printStackTrace e))
        (edn-response 500 {:success false
                           :reason  (.getMessage e)})))))

(defn app-handler
  "Builds the Ring handler stack:
   1. Reitit router matches routes to page handlers
   2. wrap-exceptions catches unhandled errors and logs stack traces
   3. wrap-file serves static assets (CSS, JS) from public/
   4. wrap-content-type sets Content-Type from file extensions
   5. wrap-not-modified adds 304 responses for unchanged assets"
  []
  (-> (reitit-ring/ring-handler
        (reitit-ring/router routes)
        (reitit-ring/create-default-handler))
      wrap-exceptions
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
  (start! {:port 3000})
  (stop!)

  ;; Test API directly
  ((app-handler) {:request-method :get :uri "/api/content"})

  ;; Test reviews API
  ((app-handler) {:request-method :get :uri "/api/reviews/due"})

  ;; Test ingest (now creates review too)
  ((app-handler) {:request-method :post
                  :uri "/api/ingest"
                  :body (java.io.StringReader.
                          (pr-str {:text "# Test\n\nHello world."
                                   :title "Test Article"}))}))
