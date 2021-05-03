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
package org.airsonic.player.controller;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.io.MoreFiles;
import org.airsonic.player.domain.*;
import org.airsonic.player.security.JWTAuthenticationToken;
import org.airsonic.player.service.*;
import org.airsonic.player.util.StringUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;

/**
 * Controller for the page used to play shared music (Twitter, Facebook etc).
 *
 * @author Sindre Mehus
 */
@Controller
@RequestMapping("/ext/share")
public class ExternalPlayerController {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalPlayerController.class);

    @Autowired
    private SettingsService settingsService;
    @Autowired
    private PlayerService playerService;
    @Autowired
    private ShareService shareService;
    @Autowired
    private MediaFileService mediaFileService;
    @Autowired
    private JWTSecurityService jwtSecurityService;
    @Autowired
    private VideoPlayerController videoPlayerController;
    @Autowired
    private CaptionsController captionsController;

    @GetMapping("/{shareName}")
    protected ModelAndView handleRequestInternal(@PathVariable("shareName") String shareName, HttpServletRequest request, HttpServletResponse response) throws Exception {
        LOG.debug("Share name is {}", shareName);

        if (StringUtils.isBlank(shareName)) {
            LOG.warn("Could not find share with shareName {}", shareName);
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }

        Share share = shareService.getShareByName(shareName);

        if (share != null && share.getExpires() != null && share.getExpires().isBefore(Instant.now())) {
            LOG.warn("Share {} has expired", shareName);
            share = null;
        }

        if (share != null) {
            share.setLastVisited(Instant.now());
            share.setVisitCount(share.getVisitCount() + 1);
            shareService.updateShare(share);
        }

        Player player = playerService.getGuestPlayer(request);

        Map<String, Object> map = new HashMap<>();

        List<MediaFileWithUrlInfo> media = getMedia(request, share, player);
        map.put("share", share);
        map.put("media", media);
        map.put("videoPresent", media.stream().anyMatch(mf -> mf.getFile().isVideo()));

        return new ModelAndView("externalPlayer", "model", map);
    }

    private List<MediaFileWithUrlInfo> getMedia(HttpServletRequest request, Share share, Player player) {
        Instant expires = null;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JWTAuthenticationToken) {
            DecodedJWT token = (DecodedJWT) authentication.getDetails();
            expires = Optional.ofNullable(token).map(x -> x.getExpiresAt()).map(x -> x.toInstant()).orElse(null);
        }
        Instant finalExpires = expires;

        List<MusicFolder> musicFolders = settingsService.getMusicFoldersForUser(player.getUsername());

        if (share != null) {
            return shareService.getSharedFiles(share.getId(), musicFolders)
                    .stream()
                    .filter(MediaFile::exists)
                    .flatMap(f -> {
                        if (f.isDirectory()) {
                            return mediaFileService.getChildrenOf(f, true, false, true).stream().map(fc -> addUrlInfo(request, player, fc, finalExpires));
                        } else {
                            return Stream.of(addUrlInfo(request, player, f, finalExpires));
                        }
                    })
                    .collect(toList());
        }

        return emptyList();
    }

    public MediaFileWithUrlInfo addUrlInfo(HttpServletRequest request, Player player, MediaFile mediaFile, Instant expires) {
        String prefix = "ext";

        boolean streamable = true;
        String contentType = StringUtil.getMimeType(MoreFiles.getFileExtension(mediaFile.getFile()));
        String streamUrl = jwtSecurityService.addJWTToken(User.USERNAME_ANONYMOUS,
                UriComponentsBuilder.fromHttpUrl(NetworkService.getBaseUrl(request) + prefix + "/stream")
                        .queryParam("id", mediaFile.getId())
                        .queryParam("player", player.getId())
                        .queryParam("format", "raw"),
                expires).build().toUriString();
        if (mediaFile.isVideo()) {
            streamable = videoPlayerController.isStreamable(mediaFile);
            if (!streamable) {
                contentType = "application/x-mpegurl";
                streamUrl = jwtSecurityService.addJWTToken(User.USERNAME_ANONYMOUS,
                        UriComponentsBuilder.fromHttpUrl(NetworkService.getBaseUrl(request) + prefix + "/hls/hls.m3u8")
                                .queryParam("id", mediaFile.getId()).queryParam("player", player.getId())
                                .queryParam("maxBitRate", VideoPlayerController.BIT_RATES),
                        expires).build().toUriString();
            }
        }

        List<CaptionsController.CaptionInfo> captions = captionsController.listCaptions(mediaFile);

        String coverArtUrl = jwtSecurityService.addJWTToken(
                User.USERNAME_ANONYMOUS,
                UriComponentsBuilder
                        .fromHttpUrl(NetworkService.getBaseUrl(request) + prefix + "/coverArt.view")
                        .queryParam("id", mediaFile.getId())
                        .queryParam("size", "500"),
                expires)
            .build().toUriString();
        return new MediaFileWithUrlInfo(mediaFile, streamable, coverArtUrl, streamUrl, captions, contentType);
    }
}
