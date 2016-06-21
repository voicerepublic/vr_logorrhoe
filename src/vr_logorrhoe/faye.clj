(ns vr-logorrhoe.faye
  "

  https://docs.cometd.org/current/apidocs/

  "
  (:require [vr-logorrhoe
             [config :as config :refer [setting state merge-state!]]]
            [taoensso.timbre :as timbre :refer [debug]]
            [clj-http.client :as client]
            [cheshire.core :as json])
  (:import [org.cometd.bayeux Message]
           [org.cometd.bayeux.client ClientSessionChannel]
           [org.cometd.client BayeuxClient]
           [org.eclipse.jetty.client HttpClient]))


(def url "http://localhost:3000/faye")

(def http-client (HttpClient.))

(.start http-client)

(def transport (LongPollingTransport. nil http-client))

(def client (BayeuxClient. url transport))
