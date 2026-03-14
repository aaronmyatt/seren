(ns seren.app.pages.layout
  "Shared HTML layout for all pages.
   Renders the full HTML5 document shell with CDN links for Open Props
   and Shoelace, plus per-page CLJS module loading.

   Each page calls `(page {:title ... :body ... :modules [...]})` where
   `:modules` is a vector of keywords matching shadow-cljs module names.
   The `:shared` module is always loaded first.

   See: https://github.com/weavejester/hiccup
   See: https://open-props.style/
   See: https://shoelace.style/getting-started/installation"
  (:require [hiccup2.core :as h]))

(defn page
  "Renders a full HTML5 page with CDN resources and CLJS module scripts.
   opts map:
     :title   - page title string
     :body    - hiccup data structure for page content
     :modules - vector of keyword module names, e.g. [:shared :library]"
  [{:keys [title body modules]}]
  (str
    (h/html
      (h/raw "<!DOCTYPE html>")
      [:html {:lang "en"}
       [:head
        [:meta {:charset "UTF-8"}]
        [:meta {:name "viewport"
                :content "width=device-width, initial-scale=1.0, user-scalable=no"}]
        [:title title]

        ;; PWA meta tags
        ;; See: https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps
        [:meta {:name "theme-color" :content "#7c3aed"}]
        [:meta {:name "apple-mobile-web-app-capable" :content "yes"}]
        [:meta {:name "apple-mobile-web-app-status-bar-style" :content "black-translucent"}]

        ;; Open Props — base design tokens from CDN
        ;; See: https://open-props.style/#getting-started
        [:link {:rel "stylesheet" :href "https://unpkg.com/open-props"}]
        [:link {:rel "stylesheet" :href "https://unpkg.com/open-props/normalize.min.css"}]

        ;; Shoelace Web Components — theme + autoloader from CDN
        ;; See: https://shoelace.style/getting-started/installation#cdn
        [:link {:rel "stylesheet"
                :href "https://cdn.jsdelivr.net/npm/@shoelace-style/shoelace@2.20.1/cdn/themes/light.css"}]
        [:script {:type "module"
                  :src "https://cdn.jsdelivr.net/npm/@shoelace-style/shoelace@2.20.1/cdn/shoelace-autoloader.js"}]

        ;; App-specific CSS
        [:link {:rel "stylesheet" :href "/css/tokens.css"}]
        [:link {:rel "stylesheet" :href "/css/app.css"}]]

       [:body
        body
        ;; Per-page CLJS modules — :shared first, then page-specific
        (for [m modules]
          [:script {:src (str "/js/" (name m) ".js")}])]])))

(comment
  (page {:title "Test"
         :body [:main [:h1 "Hello"]]
         :modules [:shared]})

  (page {:title "Library"
         :body [:main [:p "Library page"]]
         :modules [:shared :library]}))
