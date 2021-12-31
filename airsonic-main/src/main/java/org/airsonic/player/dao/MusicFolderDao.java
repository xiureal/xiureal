/*
 This file is part of Airsonic.

 Airsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Airsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Airsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2016 (C) Airsonic Authors
 Based upon Subsonic, Copyright 2009 (C) Sindre Mehus
 */
package org.airsonic.player.dao;

import org.airsonic.player.domain.MusicFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;

import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Provides database services for music folders.
 *
 * @author Sindre Mehus
 */
@Repository
public class MusicFolderDao extends AbstractDao {

    private static final Logger LOG = LoggerFactory.getLogger(MusicFolderDao.class);
    private static final String INSERT_COLUMNS = "path, name, type, enabled, changed";
    private static final String QUERY_COLUMNS = "id, " + INSERT_COLUMNS;
    public static final MusicFolderRowMapper MUSICFOLDER_ROW_MAPPER = new MusicFolderRowMapper();

    @PostConstruct
    public void register() throws Exception {
        registerInserts("music_folder", "id", Arrays.asList(INSERT_COLUMNS.split(", ")), MusicFolder.class);
    }

    /**
     * Returns all music folders.
     *
     * @return Possibly empty list of all music folders.
     */
    public List<MusicFolder> getAllMusicFolders() {
        String sql = "select " + QUERY_COLUMNS + " from music_folder";
        return query(sql, MUSICFOLDER_ROW_MAPPER);
    }

    /**
     * Return the music folder a the given path
     *
     * @return Possibly null instance of MusicFolder
     */
    public MusicFolder getMusicFolderForPath(String path) {
        String sql = "select " + QUERY_COLUMNS + " from music_folder where path = ?";
        return queryOne(sql, MUSICFOLDER_ROW_MAPPER, path);
    }

    /**
     * Creates a new music folder.
     *
     * @param musicFolder The music folder to create.
     */
    @Transactional
    public void createMusicFolder(MusicFolder musicFolder) {
        if (getMusicFolderForPath(musicFolder.getPath().toString()) == null) {
            Integer id = insert("music_folder", musicFolder);

            update("insert into music_folder_user (music_folder_id, username) select ?, username from users", id);
            musicFolder.setId(id);

            LOG.info("Created music folder {} with id {}", musicFolder.getPath(), musicFolder.getId());
        }
    }

    /**
     * Deletes the music folder with the given ID.
     *
     * @param id The music folder ID.
     */
    public void deleteMusicFolder(Integer id) {
        String sql = "delete from music_folder where id=?";
        update(sql, id);
        LOG.info("Deleted music folder with ID {}", id);
    }

    public void reassignChildren(MusicFolder from, MusicFolder to) {
        if (to.getPath().getNameCount() > from.getPath().getNameCount()) {
            // assign ancestor -> descendant
            MusicFolder ancestor = from;
            MusicFolder descendant = to;
            String relativePath = ancestor.getPath().relativize(descendant.getPath()).toString();
            // update children
            int len = relativePath.length();
            String sql = "update media_file set "
                    + "folder_id=?, "
                    + "path=SUBSTR(path, " + (len + 2) + "), "
                    + "cover_art_path=SUBSTR(cover_art_path, " + (len + 2) + "), "
                    + "parent_path=(case "
                    + "  when (length(parent_path) > " + len + ") then SUBSTR(parent_path, " + (len + 2) + ") "
                    + "  else SUBSTR(parent_path, " + (len + 1) + ") end) "
                    + "where folder_id=? and path like ?";
            update(sql, descendant.getId(), ancestor.getId(), relativePath + File.separator + "%");
            // update root
            sql = "update media_file set "
                    + "folder_id=?, "
                    + "path='', "
                    + "parent_path=null, "
                    + "cover_art_path=SUBSTR(cover_art_path, " + (len + 2) + "), "
                    + "title=?, "
                    + "type=? "
                    + "where folder_id=? and path=?";
            update(sql, descendant.getId(), descendant.getName(), MediaFile.MediaType.DIRECTORY, ancestor.getId(), relativePath);
        } else {
            // assign descendant -> ancestor
            MusicFolder ancestor = to;
            MusicFolder descendant = from;
            Path relativePath = ancestor.getPath().relativize(descendant.getPath());
            // update root
            String sql = "update media_file set "
                    + "folder_id=?, "
                    + "path=?, "
                    + "parent_path=?, "
                    + "cover_art_path=concat(?, cover_art_path)"
                    + "where folder_id=? and path=''";
            update(sql, ancestor.getId(), relativePath, relativePath.getParent() == null ? "" : relativePath.getParent().toString(), relativePath + File.separator, descendant.getId());
            // update children
            sql = "update media_file set "
                    + "folder_id=?, "
                    + "path=concat(?, path) "
                    + "parent_path=(case"
                    + "  when (parent_path = '') then ?"
                    + "  else concat(?, parent_path) end), "
                    + "cover_art_path=concat(?, cover_art_path)"
                    + "where folder_id=?";
            update(sql, ancestor.getId(), relativePath + File.separator, relativePath, relativePath + File.separator, relativePath + File.separator, descendant.getId());
        }
    }

    public void updateMusicFolder(MusicFolder musicFolder) {
        String sql = "update music_folder set path=?, name=?, type=?, enabled=?, changed=? where id=?";
        update(sql, musicFolder.getPath().toString(), musicFolder.getName(), musicFolder.getType().name(),
               musicFolder.isEnabled(), musicFolder.getChanged(), musicFolder.getId());
    }

    public List<MusicFolder> getMusicFoldersForUser(String username) {
        String sql = "select " + prefix(QUERY_COLUMNS, "music_folder") + " from music_folder, music_folder_user " +
                     "where music_folder.id = music_folder_user.music_folder_id and music_folder_user.username = ?";
        return query(sql, MUSICFOLDER_ROW_MAPPER, username);
    }

    public void setMusicFoldersForUser(String username, Collection<Integer> musicFolderIds) {
        update("delete from music_folder_user where username = ?", username);
        for (Integer musicFolderId : musicFolderIds) {
            update("insert into music_folder_user(music_folder_id, username) values (?, ?)", musicFolderId, username);
        }
    }

    public static class MusicFolderRowMapper implements RowMapper<MusicFolder> {
        @Override
        public MusicFolder mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new MusicFolder(rs.getInt(1), Paths.get(rs.getString(2)), rs.getString(3),
                    MusicFolder.Type.valueOf(rs.getString(4)), rs.getBoolean(5),
                    Optional.ofNullable(rs.getTimestamp(6)).map(x -> x.toInstant()).orElse(null));
        }
    }
}
