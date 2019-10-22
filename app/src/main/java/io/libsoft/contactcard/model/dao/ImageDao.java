package io.libsoft.contactcard.model.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import io.libsoft.contactcard.model.entity.Image;
import java.util.List;

@Dao
public interface ImageDao {

  @Insert
  Long insert(Image image);

  @Query("SELECT * FROM image WHERE contact_id=:contactId ORDER BY raw ASC")
  List<Image> getImagesByContactId(Long contactId);


}