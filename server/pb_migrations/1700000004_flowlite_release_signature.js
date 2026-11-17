/// <reference path="../pb_data/types.d.ts" />
// Linux releases carry a minisign signature from the release key kept off the server.
migrate((app) => {
  const c = app.findCollectionByNameOrId("linux_releases");
  c.fields.add(new TextField({ name: "signature", required: true, max: 2000 }));
  app.save(c);
}, (app) => {
  const c = app.findCollectionByNameOrId("linux_releases");
  c.fields.removeByName("signature");
  app.save(c);
});
