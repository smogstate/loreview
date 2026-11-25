(ns loreview.fixtures
  "Shared test data: the fixture tiles and a valid approval for them."
  (:require [clojure.edn :as edn]
            [loregate.hash :as hash]))

(def tiles (edn/read-string (slurp "dev/fixtures/tiles.edn")))
(def by-id (into {} (map (juxt :tile/id identity)) tiles))
(def unpaid (by-id :tile/unpaid-invoices))
(def invoices (by-id :tile/client-invoices))
(def contract (by-id :tile/contract-total))

(def unpaid-output
  [{:name :biz/client-name :type :string :owned? true}
   {:name :biz/unpaid-total :type :decimal :unit :currency/eur :owned? true}
   {:name :n :type :int :owned? false}])

(def unpaid-pins
  (into {[:rows :biz/client] (hash/sha256 :rule)}
        (map (fn [k] [k (hash/sha256 k)]))
        [:biz/invoice :biz/client :biz.invoice/client :biz/unpaid-total :biz/days-overdue
         :bind/invoices-parquet :bind/clients-parquet]))

(defn approval
  "The payload of an approval of `t` as version `v`."
  [t v]
  {:tile/id (:tile/id t) :tile/version v :tile/spec t
   :plan/id (hash/sha256 [(:tile/query t) v])
   :plan/output (condp = (:tile/query t)
                  (:tile/query unpaid) unpaid-output
                  (:tile/query invoices) [{:name :biz/client-name :type :string :owned? true}
                                          {:name :biz/invoice-id :type :string :owned? true}
                                          {:name :biz/due-date :type :date :owned? true}
                                          {:name :biz/days-overdue :type :int :owned? true}
                                          {:name :biz/amount :type :decimal :owned? true}]
                  [{:name :biz/contract-total :type :decimal :owned? true}])
   :plan/pins (if (= t unpaid) unpaid-pins {:biz/contract-total (hash/sha256 :ct)})
   :tile/lineage "…"
   :test/user {:roles #{"role:finance"} :region "EU"}})
