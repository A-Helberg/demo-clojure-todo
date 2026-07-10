(ns todo.ui.api
  "Thin wrapper over the browser's fetch, speaking EDN both ways. Every call
  returns a js/Promise of {:ok? bool, :status int, :body <Clojure data>}.

  Because both ends are Clojure, the wire format can be Clojure data too:
  keywords arrive as keywords, uuids as uuids, #inst timestamps as dates —
  no JSON.parse, no keywordizing, no mapping layer. The server still serves
  JSON to any client that asks (content negotiation), and JSON remains the
  format at the external Open-Meteo edge; EDN is for the edge we own on
  both sides. The session cookie rides along automatically (fetch defaults
  to same-origin credentials)."
  (:require [cljs.reader :as reader]))

(defn- parse-body [^js response]
  (-> (.text response)
      (.then (fn [text]
               (when (seq text)
                 (reader/read-string text))))))

(defn request
  ([method url] (request method url nil))
  ([method url body]
   (-> (js/fetch url
                 (clj->js (cond-> {:method  method
                                   :headers (cond-> {"Accept" "application/edn"}
                                              body (assoc "Content-Type" "application/edn"))}
                            body (assoc :body (pr-str body)))))
       (.then (fn [^js response]
                (-> (parse-body response)
                    (.then (fn [data]
                             {:ok?    (.-ok response)
                              :status (.-status response)
                              :body   data}))))))))

(def GET    (partial request "GET"))
(def POST   (partial request "POST"))
(def PATCH  (partial request "PATCH"))
(def DELETE (partial request "DELETE"))


