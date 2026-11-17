/// <reference path="../pb_data/types.d.ts" />
// Windows and Mac apps label their transcripts with their own device name.
migrate((app) => {
  const c = app.findCollectionByNameOrId("transcripts");
  c.fields.getByName("device").values = ["android", "linux", "windows", "macos"];
  app.save(c);
}, (app) => {
  const c = app.findCollectionByNameOrId("transcripts");
  c.fields.getByName("device").values = ["android", "linux"];
  app.save(c);
});
