(ns loreview.web.core
  "The web app: the user, the route, HTTP as an effect, and the pages — the list, one
  tile, one mosaic."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [loreview.web.mosaic :as mosaic]
            [loreview.web.tile :as tile]
            [re-frame.core :as rf]
            [reagent.dom.client :as rdc]))

;; --- effects ----------------------------------------------------------------

(rf/reg-fx
 :http
 (fn [{:keys [method url body token on-ok on-error]}]
   (-> (js/fetch url #js {:method (or method "GET")
                          :headers #js {"Authorization" (str "Bearer " token)
                                        "Accept" "application/edn"
                                        "Content-Type" "application/edn"}
                          :body (some-> body pr-str)})
       (.then (fn [r] (.then (.text r) (fn [t] [(.-status r) (reader/read-string t)]))))
       (.then (fn [[status body]] (rf/dispatch (conj (if (< status 300) on-ok on-error) body))))
       (.catch (fn [_] (rf/dispatch (conj on-error {:error :network/unreachable})))))))

(defn- stored-user []
  (try (js/localStorage.getItem "loreview-user") (catch :default _ nil)))

(rf/reg-fx :store-user (fn [u] (try (js/localStorage.setItem "loreview-user" u) (catch :default _ nil))))

;; --- routes -----------------------------------------------------------------

(defn- route-of [hash]
  (let [[page id v] (->> (str/split (str/replace hash #"^#/?" "") #"/") (map js/decodeURIComponent))]
    (cond
      (and (= "tiles" page) id v) [:tile (keyword id) (js/parseInt v)]
      (and (= "mosaics" page) id) [:mosaic (keyword id)]
      :else [:list])))

(defn tile-href [id v]
  (str "#/tiles/" (js/encodeURIComponent (subs (str id) 1)) "/" v))

(defn mosaic-href [id] (str "#/mosaics/" (js/encodeURIComponent (subs (str id) 1))))

(rf/reg-event-fx
 :app/route
 (fn [{:keys [db]} [_ hash]]
   (let [[page id v :as route] (route-of hash)]
     {:db (assoc db :route route)
      :dispatch (case page
                  :tile [::tile/load [id v] id v]
                  :mosaic [:mosaic/load id]
                  [:list/load])})))

(rf/reg-event-fx
 :app/init
 (fn [_ _]
   {:db {:user (or (stored-user) "ana") :tiles {}}
    :dispatch [:app/route js/location.hash]}))

(rf/reg-event-fx
 :app/set-user
 (fn [{:keys [db]} [_ u]]
   {:db (assoc db :user u :tiles {})
    :store-user u
    :dispatch [:app/route js/location.hash]}))

(rf/reg-event-fx
 :list/load
 (fn [{:keys [db]} _]
   {:dispatch-n [[:list/get :list "api/tiles"] [:list/get :mosaics "api/mosaics"]]}))

(rf/reg-event-fx
 :list/get
 (fn [{:keys [db]} [_ k url]]
   {:http {:url url :token (:user db) :on-ok [:list/received k] :on-error [:list/received k]}}))

(rf/reg-event-db :list/received (fn [db [_ k l]] (assoc db k l)))

(rf/reg-sub :route (fn [db] (:route db)))
(rf/reg-sub :user (fn [db] (:user db)))
(rf/reg-sub :list (fn [db] (:list db)))
(rf/reg-sub :mosaics (fn [db] (:mosaics db)))

;; --- views ------------------------------------------------------------------

;; ponytail: the user is picked from Loregate's fixture users; the token comes from a real
;; sign-in once Loregate validates OIDC tokens (its SPEC 8.1)
(def fixture-users ["ana" "bob" "cleo"])

(defn- header []
  (let [u @(rf/subscribe [:user])]
    [:header.top
     [:a.brand {:href "#/"} "Loreview"]
     [:label.user "Viewing as "
      [:select {:value u :on-change #(rf/dispatch [:app/set-user (.. % -target -value)])}
       (for [x fixture-users] ^{:key x} [:option {:value x} x])]]]))

(defn- tile-list []
  (let [l @(rf/subscribe [:list])
        ms @(rf/subscribe [:mosaics])]
    [:main.list
     (when (and (sequential? ms) (seq ms))
       [:<> [:h1 "Pages"]
        [:ul (for [{:mosaic/keys [id title]} ms]
               ^{:key id} [:li [:a {:href (mosaic-href id)} title]])]])
     [:h1 "Tiles"]
     (cond
       (nil? l) [:p.muted "Loading…"]
       (:error l) [:p.error (tile/error-text (:error l))]
       (empty? l) [:p.muted "No tiles yet."]
       :else [:ul (for [{:tile/keys [id version title]} l]
                    ^{:key id} [:li [:a {:href (tile-href id version)} title]])])]))

(defn app []
  (let [[page id v] @(rf/subscribe [:route])]
    [:<>
     [header]
     (case page
       :tile [:main.single [tile/tile-card [id v]]]
       :mosaic [:main.wide [mosaic/page]]
       [tile-list])]))

(defonce root (atom nil))

(defn ^:dev/after-load mount []
  (rdc/render @root [app]))

(defn init []
  (reset! root (rdc/create-root (js/document.getElementById "app")))
  (rf/dispatch-sync [:app/init])
  (js/addEventListener "hashchange" #(rf/dispatch [:app/route js/location.hash]))
  (mount))
