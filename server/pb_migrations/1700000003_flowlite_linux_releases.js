/// <reference path="../pb_data/types.d.ts" />
// Linux app updates, kept apart from app_releases so phones never see a .deb.
// Same rules: signed-in devices read, only the superuser uploads (linux/publish.sh), protected file.
migrate((app) => {
  app.save(new Collection({
    type: "base",
    name: "linux_releases",
    listRule: '@request.auth.id != ""',
    viewRule: '@request.auth.id != ""',
    createRule: null,
    updateRule: null,
    deleteRule: null,
    fields: [
      { name: "version_code", type: "number", required: true, onlyInt: true },
      { name: "sha256", type: "text", required: true, min: 64, max: 64, pattern: "^[0-9a-f]{64}$" },
      { name: "deb", type: "file", required: true, maxSelect: 1, maxSize: 300 * 1024 * 1024, protected: true },
      { name: "created", type: "autodate", onCreate: true },
    ],
  }));
}, (app) => {
  app.delete(app.findCollectionByNameOrId("linux_releases"));
});
