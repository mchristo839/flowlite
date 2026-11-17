/// <reference path="../pb_data/types.d.ts" />
// Android app updates. Signed-in devices can read; only the superuser uploads (android/publish.sh).
// The APK is a protected file, so downloading it needs a file token from a signed-in device.
migrate((app) => {
  app.save(new Collection({
    type: "base",
    name: "app_releases",
    listRule: '@request.auth.id != ""',
    viewRule: '@request.auth.id != ""',
    createRule: null,
    updateRule: null,
    deleteRule: null,
    fields: [
      { name: "version_code", type: "number", required: true, onlyInt: true },
      { name: "version_name", type: "text", max: 50 },
      { name: "sha256", type: "text", required: true, min: 64, max: 64, pattern: "^[0-9a-f]{64}$" },
      { name: "apk", type: "file", required: true, maxSelect: 1, maxSize: 300 * 1024 * 1024, protected: true },
      { name: "created", type: "autodate", onCreate: true },
    ],
  }));
}, (app) => {
  app.delete(app.findCollectionByNameOrId("app_releases"));
});
