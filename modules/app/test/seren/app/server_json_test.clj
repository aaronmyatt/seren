(ns seren.app.server-json-test
  "Tests for JSON support in the ingest API endpoint.

   Verifies that Chrome extensions and bookmarklets can POST JSON
   payloads and receive JSON responses.

   See plan-url-fetching.md § 'Accept JSON'"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [seren.app.server :as server]))

(defn- make-request
  "Creates a minimal Ring request map for testing."
  [method uri body & {:keys [content-type] :or {content-type "application/edn"}}]
  {:request-method method
   :uri            uri
   :headers        {"content-type" content-type}
   :body           (java.io.StringReader. body)})

(deftest json-ingest-test
  (testing "accepts JSON body and returns JSON response"
    (let [handler (server/app-handler)
          request (make-request
                    :post "/api/ingest"
                    (json/write-str {:text "Test article about Clojure."
                                     :title "JSON Test"})
                    :content-type "application/json")
          response (handler request)]
      (is (= "application/json" (get-in response [:headers "Content-Type"]))
          "Should return JSON content type for JSON requests")
      (let [body (json/read-str (:body response) :key-fn keyword)]
        (is (:success body)
            "Should successfully ingest from JSON payload")
        (is (= "JSON Test" (get-in body [:content :title]))))))

  (testing "EDN requests still work (backwards compatibility)"
    (let [handler (server/app-handler)
          request (make-request
                    :post "/api/ingest"
                    (pr-str {:text "Test article via EDN."
                             :title "EDN Test"})
                    :content-type "application/edn")
          response (handler request)]
      (is (= "application/edn" (get-in response [:headers "Content-Type"]))
          "Should return EDN content type for EDN requests")
      (let [body (edn/read-string (:body response))]
        (is (:success body)
            "Should successfully ingest from EDN payload")
        (is (= "EDN Test" (get-in body [:content :title]))))))

  (testing "JSON URL-only payload is accepted"
    (let [handler (server/app-handler)
          ;; This is what a Chrome extension would send
          request (make-request
                    :post "/api/ingest"
                    (json/write-str {:url "https://clojure.org/about/rationale"})
                    :content-type "application/json")
          response (handler request)]
      ;; This will either succeed (if network available) or fail with a reason
      (let [body (json/read-str (:body response) :key-fn keyword)]
        (is (contains? body :success)
            "Should return a success/failure result")
        (is (= "application/json" (get-in response [:headers "Content-Type"]))
            "Should respond in JSON for JSON requests")))))
