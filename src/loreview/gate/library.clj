(ns loreview.gate.library
  "The gate over an embedded Loregate (`loregate.core`), in-process. Used where Loreview
  runs Loregate as a library: tests, development, and a single-process deployment. The
  token is resolved to a Loregate user by `users` (token → {:user/id :user/attrs}) — the
  identity check a Loregate server would make from the token itself."
  (:require [loregate.core :as lg]
            [loreview.gate.port :as port]))

(defn- compiled [res]
  (if-let [es (:errors res)]
    {:error (:error (first es)) :errors es}
    res))

(defrecord LibraryGate [sys users]
  port/Gate
  (compile-query [_ token query]
    (if (users token) (compiled (lg/compile sys query)) {:error :auth/invalid-token}))
  (run-plan [_ token plan-id query params]
    (if-let [u (users token)]
      (compiled (lg/run sys {:plan-id plan-id :query query :params params :user u}))
      {:error :auth/invalid-token}))
  (run-query [_ token query params]
    (if-let [u (users token)]
      (compiled (lg/query sys {:query query :params params :user u}))
      {:error :auth/invalid-token}))
  (publish! [_ token plan-id name description]
    (if-let [u (users token)]
      (lg/publish sys {:plan-id plan-id :name name :description description :user u})
      {:error :auth/invalid-token}))
  (unpublish! [_ token plan-id]
    (if-let [u (users token)]
      (lg/unpublish sys {:plan-id plan-id :user u})
      {:error :auth/invalid-token}))
  ;; depends on no user's rights (Loregate SPEC 10): any known principal may ask
  (plan-status [_ token plan-id]
    (if (users token) (compiled (lg/plan-status sys {:plan-id plan-id})) {:error :auth/invalid-token})))

(defn gate [sys users] (->LibraryGate sys users))
