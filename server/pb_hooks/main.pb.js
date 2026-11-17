/// <reference path="../pb_data/types.d.ts" />
// Last write wins on the device clock: reject an update whose updated_at is older
// than the stored one. Clients treat 409 as "server already has newer data".
onRecordUpdateRequest((e) => {
  const stored = e.record.original().getInt("updated_at");
  const incoming = e.record.getInt("updated_at");
  if (incoming < stored) {
    throw new ApiError(409, "stale update: server has a newer version", {});
  }
  e.next();
}, "transcripts", "dictionary", "settings");
