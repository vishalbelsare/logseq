(ns frontend.handler.plugin
  (:require [promesa.core :as p]
            [rum.core :as rum]
            [frontend.util :as util]
            [frontend.fs :as fs]
            [frontend.format.mldoc :refer [->MldocMode] :as mldoc]
            [frontend.handler.notification :as notifications]
            [frontend.storage :as storage]
            [camel-snake-kebab.core :as csk]
            [frontend.state :as state]
            [medley.core :as md]
            [electron.ipc :as ipc]
            [cljs-bean.core :as bean]
            [clojure.string :as string]
            [lambdaisland.glogi :as log]
            [frontend.format :as format]))

(defonce lsp-enabled?
         (and (util/electron?)
              (= (storage/get "developer-mode") "true")))

(defn invoke-exported-api
  [type & args]
  (try
    (apply js-invoke js/logseq.api type args)
    (catch js/Error e (js/console.error e))))

;; state handlers
(defonce central-endpoint "https://raw.githubusercontent.com/xyhp915/lsp/main/")
(defonce plugins-url (str central-endpoint "plugins.json"))
(defonce stats-url (str central-endpoint "stats.json"))

(defn gh-repo-url [repo]
  (str "https://github.com/" repo))

(defn pkg-asset [id asset]
  (if-let [asset (and asset (string/replace asset #"^[./]+" ""))]
    (str central-endpoint "packages/" id "/" asset)))

(defn load-marketplace-plugins
  [refresh?]
  (if (or refresh? (nil? (:plugin/marketplace-pkgs @state/state)))
    (p/create
      (fn [resolve reject]
        (util/fetch plugins-url
                    (fn [res]
                      (let [pkgs (:packages res)]
                        (state/set-state! :plugin/marketplace-pkgs pkgs)
                        (resolve pkgs)))
                    reject)))
    (p/resolved nil)))

(defn load-marketplace-stats
  [refresh?]
  (if (or refresh? (nil? (:plugin/marketplace-stats @state/state)))
    (p/create
      (fn [resolve reject]
        (util/fetch stats-url
                    (fn [res]
                      (state/set-state! :plugin/marketplace-stats res)
                      (resolve nil))
                    reject)))
    (p/resolved nil)))

(defn installed?
  [id]
  (and (contains? (:plugin/installed-plugins @state/state) (keyword id))
       (get-in @state/state [:plugin/installed-plugins (keyword id) :iir])))

(defn install-marketplace-plugin
  [{:keys [repo id] :as item}]
  (when-not (and (:plugin/installing @state/state)
                 (installed? id))
    (p/create
      (fn [resolve]
        (state/set-state! :plugin/installing item)
        (ipc/ipc "installMarketPlugin" item)
        (resolve id)))))

(defn- init-install-listener!
  []
  (js/window.apis.on "lsp-installed"
                     (fn [^js e]
                       (js/console.log e)
                       (when-let [{:keys [status payload]} (bean/->clj e)]
                         (case (keyword status)

                           :completed
                           (let [{:keys [id dst name]} payload]
                             (js/LSPluginCore.register (bean/->js {:key id :url dst}))
                             (notifications/show!
                               (str "Installed Plugin: " name) :success))

                           :error
                           (do
                             (notifications/show!
                               (str "[Install Error] " payload) :error)
                             (js/console.error payload))

                           :dunno))

                       ;; reset
                       (state/set-state! :plugin/installing nil)
                       true)))


(defn register-plugin
  [pl]
  (swap! state/state update-in [:plugin/installed-plugins] assoc (keyword (:id pl)) pl))

(defn unregister-plugin
  [id]
  (js/LSPluginCore.unregister id))

(defn host-mounted!
  []
  (and lsp-enabled? (js/LSPluginCore.hostMounted)))

(defn register-plugin-slash-command
  [pid [cmd actions]]
  (when-let [pid (keyword pid)]
    (when (contains? (:plugin/installed-plugins @state/state) pid)
      (do (swap! state/state update-in [:plugin/installed-commands pid]
                 (fnil merge {}) (hash-map cmd (mapv #(conj % {:pid pid}) actions)))
          true))))

(defn unregister-plugin-slash-command
  [pid]
  (swap! state/state md/dissoc-in [:plugin/installed-commands (keyword pid)]))

(defn register-plugin-simple-command
  ;; action => [:action-key :event-key]
  [pid {:keys [key label type] :as cmd} action]
  (when-let [pid (keyword pid)]
    (when (contains? (:plugin/installed-plugins @state/state) pid)
      (do (swap! state/state update-in [:plugin/simple-commands pid]
                 (fnil conj []) [type cmd action pid])
          true))))

(defn unregister-plugin-simple-command
  [pid]
  (swap! state/state md/dissoc-in [:plugin/simple-commands (keyword pid)]))

(defn register-plugin-ui-item
  [pid {:keys [key type template] :as opts}]
  (when-let [pid (keyword pid)]
    (when (contains? (:plugin/installed-plugins @state/state) pid)
      (do (swap! state/state update-in [:plugin/installed-ui-items pid]
                 (fnil conj []) [type opts pid])
          true))))

(defn unregister-plugin-ui-items
  [pid]
  (swap! state/state assoc-in [:plugin/installed-ui-items (keyword pid)] []))

(defn update-plugin-settings
  [id settings]
  (swap! state/state update-in [:plugin/installed-plugins id] assoc :settings settings))

(defn parse-user-md-content
  [content {:keys [url]}]
  (try
    (if-not (string/blank? content)
      (let [content (if-not (string/blank? url)
                      (string/replace
                        content #"!\[[^\]]*\]\((.*?)\s*(\"(?:.*[^\"])\")?\s*\)"
                        (fn [[matched link]]
                          (if (and link (not (string/starts-with? link "http")))
                            (string/replace matched link (util/node-path.join url link))
                            matched)))
                      content)]
        (format/to-html content :markdown (mldoc/default-config :markdown))))
    (catch js/Error e
      (log/error :parse-user-md-exception e)
      content)))

(defn open-readme!
  [url item display]
  (if url
    ;; local
    (-> (p/let [content (invoke-exported-api "load_plugin_readme" url)
                content (parse-user-md-content content item)]
          (and (string/blank? (string/trim content)) (throw nil))
          (state/set-state! :plugin/active-readme [content item])
          (state/set-modal! display))
        (p/catch #(do (js/console.warn %)
                      (notifications/show! "No README content." :warn))))
    ;; market
    (notifications/show! (:repo item) :success)))

(defn load-unpacked-plugin
  []
  (if util/electron?
    (p/let [path (ipc/ipc "openDialogSync")]
      (when-not (:plugin/selected-unpacked-pkg @state/state)
        (state/set-state! :plugin/selected-unpacked-pkg path)))))

(defn reset-unpacked-state
  []
  (state/set-state! :plugin/selected-unpacked-pkg nil))

(defn hook-plugin
  [tag type payload plugin-id]
  (when lsp-enabled?
    (js-invoke js/LSPluginCore
               (str "hook" (string/capitalize (name tag)))
               (name type)
               (if (coll? payload)
                 (bean/->js (into {} (for [[k v] payload] [(csk/->camelCase k) (if (uuid? v) (str v) v)])))
                 payload)
               (if (keyword? plugin-id) (name plugin-id) plugin-id))))

(defn hook-plugin-app
  ([type payload] (hook-plugin-app type payload nil))
  ([type payload plugin-id] (hook-plugin :app type payload plugin-id)))

(defn hook-plugin-editor
  ([type payload] (hook-plugin-editor type payload nil))
  ([type payload plugin-id] (hook-plugin :editor type payload plugin-id)))

(defn get-ls-dotdir-root
  []
  (ipc/ipc "getLogseqDotDirRoot"))

(defn- get-user-default-plugins
  []
  (p/catch
    (p/let [files ^js (ipc/ipc "getUserDefaultPlugins")
            files (js->clj files)]
      (map #(hash-map :url %) files))
    (fn [e]
      (js/console.error e))))

;; components
(rum/defc lsp-indicator < rum/reactive
  []
  (let [text (state/sub :plugin/indicator-text)]
    (if (= text "END")
      [:span]
      [:div
       {:style
        {:width           "100%"
         :height          "100vh"
         :display         "flex"
         :align-items     "center"
         :justify-content "center"}}
       [:span
        {:style
         {:color     "#aaa"
          :font-size "38px"}} (or text "Loading ...")]])))

(defn init-plugins!
  [callback]

  (let [el (js/document.createElement "div")]
    (.appendChild js/document.body el)
    (rum/mount
      (lsp-indicator) el))

  (state/set-state! :plugin/indicator-text "Loading...")

  (p/then
    (p/let [root (get-ls-dotdir-root)
            _ (.setupPluginCore js/LSPlugin (bean/->js {:localUserConfigRoot root :dotConfigRoot root}))
            _ (doto js/LSPluginCore
                (.on "registered"
                     (fn [^js pl]
                       (register-plugin
                         (bean/->clj (.parse js/JSON (.stringify js/JSON pl))))))

                (.on "unregistered" (fn [pid]
                                      (let [pid (keyword pid)]
                                        ;; plugins
                                        (swap! state/state md/dissoc-in [:plugin/installed-plugins pid])
                                        ;; commands
                                        (unregister-plugin-slash-command pid)
                                        (unregister-plugin-simple-command pid)
                                        (unregister-plugin-ui-items pid)
                                        )))

                (.on "unlink-plugin" (fn [pid]
                                       (let [pid (keyword pid)]
                                         (ipc/ipc "uninstallMarketPlugin" (name pid)))))

                (.on "disabled" (fn [pid]
                                  (unregister-plugin-slash-command pid)
                                  (unregister-plugin-simple-command pid)
                                  (unregister-plugin-ui-items pid)))

                (.on "theme-changed" (fn [^js themes]
                                       (swap! state/state assoc :plugin/installed-themes
                                              (vec (mapcat (fn [[_ vs]] (bean/->clj vs)) (bean/->clj themes))))))

                (.on "theme-selected" (fn [^js opts]
                                        (let [opts (bean/->clj opts)
                                              url (:url opts)
                                              mode (:mode opts)]
                                          (when mode (state/set-theme! mode))
                                          (state/set-state! :plugin/selected-theme url))))

                (.on "settings-changed" (fn [id ^js settings]
                                          (let [id (keyword id)]
                                            (when (and settings
                                                       (contains? (:plugin/installed-plugins @state/state) id))
                                              (update-plugin-settings id (bean/->clj settings)))))))

            default-plugins (get-user-default-plugins)

            _ (.register js/LSPluginCore (bean/->js (if (seq default-plugins) default-plugins [])) true)])
    #(do
       (state/set-state! :plugin/indicator-text "END")
       (callback))))

(defn setup!
  "setup plugin core handler"
  [callback]
  (if (not lsp-enabled?)
    (callback)
    (do
      (init-install-listener!)
      (init-plugins! callback))))
