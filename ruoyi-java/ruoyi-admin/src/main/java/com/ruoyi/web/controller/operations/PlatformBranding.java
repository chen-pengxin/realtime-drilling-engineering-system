package com.ruoyi.web.controller.operations;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.domain.AjaxResult;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class PlatformBranding {
    private final OperationsSupport ops;
    public PlatformBranding(OperationsSupport ops) { this.ops=ops; }

    @PostMapping("/api/access/me/avatar")
    public AjaxResult avatar(@RequestParam("file") MultipartFile file) throws IOException {
        var image=readImage(file);
        var dir=Path.of(RuoYiConfig.getAvatarPath());
        Files.createDirectories(dir);
        String name=UUID.randomUUID()+".png";
        ImageIO.write(image,"png",dir.resolve(name).toFile());
        var user=com.ruoyi.common.utils.ShiroUtils.getSysUser();
        String path="/profile/avatar/"+name;
        ops.jdbc.update("UPDATE sys_user SET avatar=? WHERE user_id=?",path,user.getUserId());
        user.setAvatar(path);
        com.ruoyi.common.utils.ShiroUtils.setSysUser(user);
        return AjaxResult.success();
    }
    @GetMapping("/branding/settings")
    public AjaxResult branding() {
        Map<String,Object> values = new LinkedHashMap<>();
        for (String key : List.of("logo","favicon","loginImage","title","preset","home"))
            values.put(key,ops.setting("drilling.ui."+key));
        values.put("systemName",ops.setting("drilling.system.name"));
        return AjaxResult.success(values);
    }
    static Path directory() { return Path.of(RuoYiConfig.getProfile(),"branding"); }

    static BufferedImage readImage(MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize()>5*1024*1024) throw new IllegalArgumentException("图片不能超过 5 MB");
        try (var input=ImageIO.createImageInputStream(file.getInputStream())) {
            var readers=ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("请选择 PNG 或 JPEG 图片");
            var reader=readers.next();
            try {
                reader.setInput(input,true,true);
                if (!Set.of("png","jpeg","jpg").contains(reader.getFormatName().toLowerCase(Locale.ROOT))
                    || (long)reader.getWidth(0)*reader.getHeight(0)>12000000)
                    throw new IllegalArgumentException("仅支持 1200 万像素以内的 PNG 或 JPEG 图片");
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }
    static String save(MultipartFile file) throws IOException {
        var image=readImage(file);
        Files.createDirectories(directory());
        String name=UUID.randomUUID()+".png";
        ImageIO.write(image,"png",directory().resolve(name).toFile());
        return "/branding/"+name;
    }
    @GetMapping("/branding/{name:[0-9a-f-]+[.]png}")
    public ResponseEntity<byte[]> image(@PathVariable String name) throws IOException {
        Path path=directory().resolve(name);
        if (!Files.isRegularFile(path)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(Files.readAllBytes(path));
    }
}
