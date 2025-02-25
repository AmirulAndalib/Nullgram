package org.telegram.messenger;

import android.os.Looper;
import android.util.LongSparseArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.ui.Storage.CacheModel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class FilePathDatabase {

    private final DispatchQueue dispatchQueue;
    private final int currentAccount;

    private SQLiteDatabase database;
    private File cacheFile;
    private File shmCacheFile;

    private final static int LAST_DB_VERSION = 4;

    private final static String DATABASE_NAME = "file_to_path";
    private final static String DATABASE_BACKUP_NAME = "file_to_path_backup";

    public final static int MESSAGE_TYPE_VIDEO_MESSAGE = 0;

    private final FileMeta metaTmp = new FileMeta();

    public FilePathDatabase(int currentAccount) {
        this.currentAccount = currentAccount;
        dispatchQueue = new DispatchQueue("files_database_queue_" + currentAccount);
        dispatchQueue.postRunnable(() -> {
            createDatabase(0, false);
        });
    }

    public void createDatabase(int tryCount, boolean fromBackup) {
        File filesDir = ApplicationLoader.getFilesDirFixed();
        if (currentAccount != 0) {
            filesDir = new File(filesDir, "account" + currentAccount + "/");
            filesDir.mkdirs();
        }
        cacheFile = new File(filesDir, DATABASE_NAME + ".db");
        shmCacheFile = new File(filesDir, DATABASE_NAME + ".db-shm");

        boolean createTable = false;

        if (!cacheFile.exists()) {
            createTable = true;
        }
        try {
            database = new SQLiteDatabase(cacheFile.getPath());
            database.executeFast("PRAGMA secure_delete = ON").stepThis().dispose();
            database.executeFast("PRAGMA temp_store = MEMORY").stepThis().dispose();

            if (createTable) {
                database.executeFast("CREATE TABLE paths(document_id INTEGER, dc_id INTEGER, type INTEGER, path TEXT, PRIMARY KEY(document_id, dc_id, type));").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS path_in_paths ON paths(path);").stepThis().dispose();

                database.executeFast("CREATE TABLE paths_by_dialog_id(path TEXT PRIMARY KEY, dialog_id INTEGER, message_id INTEGER, message_type INTEGER);").stepThis().dispose();

                database.executeFast("PRAGMA user_version = " + LAST_DB_VERSION).stepThis().dispose();
            } else {
                int version = database.executeInt("PRAGMA user_version");
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("current files db version = " + version);
                }
                if (version == 0) {
                    throw new Exception("malformed");
                }
                migrateDatabase(version);
                //migration
            }
            if (!fromBackup) {
                createBackup();
            }
            FileLog.d("files db created from_backup= " + fromBackup);
        } catch (Exception e) {
            if (tryCount < 4) {
                if (!fromBackup && restoreBackup()) {
                    createDatabase(tryCount + 1, true);
                    return;
                } else {
                    cacheFile.delete();
                    shmCacheFile.delete();
                    createDatabase(tryCount + 1, false);
                }
            }
            if (BuildVars.DEBUG_VERSION) {
                FileLog.e(e);
            }
        }
    }

    private void migrateDatabase(int version) throws SQLiteException {
        if (version == 1) {
            database.executeFast("CREATE INDEX IF NOT EXISTS path_in_paths ON paths(path);").stepThis().dispose();
            database.executeFast("PRAGMA user_version = " + 2).stepThis().dispose();
            version = 2;
        }
        if (version == 2) {
            database.executeFast("CREATE TABLE paths_by_dialog_id(path TEXT PRIMARY KEY, dialog_id INTEGER);").stepThis().dispose();
            database.executeFast("PRAGMA user_version = " + 3).stepThis().dispose();
            version = 3;
        }
        if (version == 3) {
            database.executeFast("ALTER TABLE paths_by_dialog_id ADD COLUMN message_id INTEGER default 0").stepThis().dispose();
            database.executeFast("ALTER TABLE paths_by_dialog_id ADD COLUMN message_type INTEGER default 0").stepThis().dispose();
            database.executeFast("PRAGMA user_version = " + 4).stepThis().dispose();
        }
    }

    private void createBackup() {
        File filesDir = ApplicationLoader.getFilesDirFixed();
        if (currentAccount != 0) {
            filesDir = new File(filesDir, "account" + currentAccount + "/");
            filesDir.mkdirs();
        }
        File backupCacheFile = new File(filesDir, DATABASE_BACKUP_NAME + ".db");
        try {
            AndroidUtilities.copyFile(cacheFile, backupCacheFile);
            FileLog.d("file db backup created " + backupCacheFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean restoreBackup() {
        File filesDir = ApplicationLoader.getFilesDirFixed();
        if (currentAccount != 0) {
            filesDir = new File(filesDir, "account" + currentAccount + "/");
            filesDir.mkdirs();
        }
        File backupCacheFile = new File(filesDir, DATABASE_BACKUP_NAME + ".db");
        if (!backupCacheFile.exists()) {
            return false;
        }
        try {
            return AndroidUtilities.copyFile(backupCacheFile, cacheFile);
        } catch (IOException e) {
            FileLog.e(e);
        }
        return false;
    }

    public String getPath(long documentId, int dc, int type, boolean useQueue) {
        if (useQueue) {
            if (BuildVars.DEBUG_VERSION) {
                if (dispatchQueue.getHandler() != null && Thread.currentThread() == dispatchQueue.getHandler().getLooper().getThread()) {
                    throw new RuntimeException("Error, lead to infinity loop");
                }
            }

            CountDownLatch syncLatch = new CountDownLatch(1);
            String[] res = new String[1];

            dispatchQueue.postRunnable(() -> {
                if (database != null) {
                    SQLiteCursor cursor = null;
                    try {
                        cursor = database.queryFinalized("SELECT path FROM paths WHERE document_id = " + documentId + " AND dc_id = " + dc + " AND type = " + type);
                        if (cursor.next()) {
                            res[0] = cursor.stringValue(0);
                            if (BuildVars.DEBUG_VERSION) {
                                // FileLog.d("get file path id=" + documentId + " dc=" + dc + " type=" + type + " path=" + res[0]);
                            }
                        }
                    } catch (SQLiteException e) {
                        FileLog.e(e);
                    } finally {
                        if (cursor != null) {
                            cursor.dispose();
                        }
                    }
                }
                syncLatch.countDown();
            });
            try {
                syncLatch.await();
            } catch (Exception ignore) {
            }
            return res[0];
        } else {
            if (database == null) {
                return null;
            }
            SQLiteCursor cursor = null;
            String res = null;
            try {
                cursor = database.queryFinalized("SELECT path FROM paths WHERE document_id = " + documentId + " AND dc_id = " + dc + " AND type = " + type);
                if (cursor.next()) {
                    res = cursor.stringValue(0);
                    // FileLog.d("get file path id=" + documentId + " dc=" + dc + " type=" + type + " path=" + res);
                    
                }
            } catch (SQLiteException e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            return res;
        }
    }

    public void putPath(long id, int dc, int type, String path) {
        dispatchQueue.postRunnable(() -> {
            // if (BuildVars.DEBUG_VERSION) {
                // FileLog.d("put file path id=" + id + " dc=" + dc + " type=" + type + " path=" + path);
            // }
            if (database == null) {
                return;
            }
            SQLitePreparedStatement state = null;
            SQLitePreparedStatement deleteState = null;
            try {
                if (path != null) {
                    deleteState = database.executeFast("DELETE FROM paths WHERE path = ?");
                    deleteState.bindString(1, path);
                    deleteState.step();

                    state = database.executeFast("REPLACE INTO paths VALUES(?, ?, ?, ?)");
                    state.requery();
                    state.bindLong(1, id);
                    state.bindInteger(2, dc);
                    state.bindInteger(3, type);
                    state.bindString(4, path);
                    state.step();
                    state.dispose();
                } else {
                    database.executeFast("DELETE FROM paths WHERE document_id = " + id + " AND dc_id = " + dc + " AND type = " + type).stepThis().dispose();
                }
            } catch (SQLiteException e) {
                FileLog.e(e);
            } finally {
                if (deleteState != null) {
                    deleteState.dispose();
                }
                if (state != null) {
                    state.dispose();
                }
            }
        });
    }

    public void checkMediaExistance(ArrayList<MessageObject> messageObjects) {
        if (messageObjects.isEmpty()) {
            return;
        }
        ArrayList<MessageObject> arrayListFinal = new ArrayList<>(messageObjects);

        CountDownLatch syncLatch = new CountDownLatch(1);
        long time = System.currentTimeMillis();
        dispatchQueue.postRunnable(() -> {
            try {
                for (int i = 0; i < arrayListFinal.size(); i++) {
                    MessageObject messageObject = arrayListFinal.get(i);
                    messageObject.checkMediaExistance(false);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            syncLatch.countDown();
        });

        try {
            syncLatch.await();
        } catch (InterruptedException e) {
            FileLog.e(e);
        }

        FileLog.d("checkMediaExistance size=" + messageObjects.size() + " time=" + (System.currentTimeMillis() - time));

        if (BuildVars.DEBUG_VERSION) {
            if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
                FileLog.e(new Exception("warning, not allowed in main thread"));
            }
        }
    }

    public void clear() {
        dispatchQueue.postRunnable(() -> {
            try {
                database.executeFast("DELETE FROM paths WHERE 1").stepThis().dispose();
                database.executeFast("DELETE FROM paths_by_dialog_id WHERE 1").stepThis().dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public boolean hasAnotherRefOnFile(String path) {
        CountDownLatch syncLatch = new CountDownLatch(1);
        boolean[] res = new boolean[]{false};
        dispatchQueue.postRunnable(() -> {
            try {
                SQLiteCursor cursor = database.queryFinalized("SELECT document_id FROM paths WHERE path = '" + path + "'");
                if (cursor.next()) {
                    res[0] = true;
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            syncLatch.countDown();
        });

        try {
            syncLatch.await();
        } catch (InterruptedException e) {
            FileLog.e(e);
        }
        return res[0];
    }

    public void saveFileDialogId(File file,FileMeta fileMeta) {
        if (file == null || fileMeta == null) {
            return;
        }
        dispatchQueue.postRunnable(() -> {
            SQLitePreparedStatement state = null;
            try {
                state = database.executeFast("REPLACE INTO paths_by_dialog_id VALUES(?, ?, ?, ?)");
                state.requery();
                state.bindString(1, shield(file.getPath()));
                state.bindLong(2, fileMeta.dialogId);
                state.bindInteger(3, fileMeta.messageId);
                state.bindInteger(4, fileMeta.messageType);
                state.step();
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
               if (state != null) {
                   state.dispose();
               }
            }
        });
    }

    public FileMeta getFileDialogId(File file, FileMeta metaTmp) {
        if (file == null) {
            return null;
        }
        if (metaTmp == null) {
            metaTmp = this.metaTmp;
        }
        long dialogId = 0;
        int messageId = 0;
        int messageType = 0;
        SQLiteCursor cursor = null;
        try {
            cursor = database.queryFinalized("SELECT dialog_id, message_id, message_type FROM paths_by_dialog_id WHERE path = '" + shield(file.getPath()) + "'");
            if (cursor.next()) {
                dialogId = cursor.longValue(0);
                messageId = cursor.intValue(1);
                messageType = cursor.intValue(2);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        metaTmp.dialogId = dialogId;
        metaTmp.messageId = messageId;
        metaTmp.messageType = messageType;
        return metaTmp;
    }

    private String shield(String path) {
        return path.replace("'","").replace("\"","");
    }

    public DispatchQueue getQueue() {
        return dispatchQueue;
    }

    public void removeFiles(List<CacheModel.FileInfo> filesToRemove) {
        dispatchQueue.postRunnable(() -> {
            try {
                database.beginTransaction();
                for (int i = 0; i < filesToRemove.size(); i++) {
                    database.executeFast("DELETE FROM paths_by_dialog_id WHERE path = '" + shield(filesToRemove.get(i).file.getPath()) + "'").stepThis().dispose();
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                database.commitTransaction();
            }
        });
    }

    public LongSparseArray<ArrayList<CacheByChatsController.KeepMediaFile>> lookupFiles(ArrayList<? extends CacheByChatsController.KeepMediaFile> keepMediaFiles) {
        CountDownLatch syncLatch = new CountDownLatch(1);
        LongSparseArray<ArrayList<CacheByChatsController.KeepMediaFile>> filesByDialogId = new LongSparseArray<>();
        dispatchQueue.postRunnable(() -> {
            try {
                FileMeta fileMetaTmp = new FileMeta();
                for (int i = 0; i < keepMediaFiles.size(); i++) {
                    FileMeta fileMeta = getFileDialogId(keepMediaFiles.get(i).file, fileMetaTmp);
                    if (fileMeta != null && fileMeta.dialogId != 0) {
                        ArrayList<CacheByChatsController.KeepMediaFile> list = filesByDialogId.get(fileMeta.dialogId);
                        if (list == null) {
                            list = new ArrayList<>();
                            filesByDialogId.put(fileMeta.dialogId, list);
                        }
                        list.add(keepMediaFiles.get(i));
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            syncLatch.countDown();
        });
        try {
            syncLatch.await();
        } catch (InterruptedException e) {
            FileLog.e(e);
        }
        return filesByDialogId;
    }

    public static class PathData {
        public final long id;
        public final int dc;
        public final int type;

        public PathData(long documentId, int dcId, int type) {
            this.id = documentId;
            this.dc = dcId;
            this.type = type;
        }
    }

    public static class FileMeta {
        public long dialogId;
        public int messageId;
        public int messageType;
        public long messageSize;
    }
}
