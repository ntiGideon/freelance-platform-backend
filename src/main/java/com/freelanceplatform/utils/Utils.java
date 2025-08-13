package com.freelanceplatform.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class Utils {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger( Utils.class );
    
    private Utils () {
    } // prevent instantiation
    
    public static List<String> parsePreferredJobCategories (Object categoriesObj) {
        if ( categoriesObj == null ) return List.of();
        
        try {
            if ( categoriesObj instanceof String jsonStr ) {
                // Frontend sent JSON.stringify output
                return mapper.readValue( jsonStr, new TypeReference<>() {
                } );
            } else if ( categoriesObj instanceof List<?> ) {
                // Already a list
                return ( (List<?>) categoriesObj ).stream()
                        .filter( Objects::nonNull )
                        .map( Object::toString )
                        .collect( Collectors.toList() );
            }
        } catch ( Exception e ) {
            log.error( e.getMessage() );
        }
        return List.of();
    }
}
