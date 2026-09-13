package kr.co.teambrain.marvelrun.user.event.query.assembler;


import kr.co.teambrain.marvelrun.user.event.query.dto.response.RegistrationOptionResponse;

import kr.co.teambrain.marvelrun.user.event.query.repository.projection.RegistrationOptionProjection;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RegistrationOptionResponseAssembler {


    public RegistrationOptionResponse assemble(
            List<RegistrationOptionProjection> projections
    ) {

        Map<String, CategoryAccumulator> categoryMap =
                new LinkedHashMap<>();


        for (
                RegistrationOptionProjection projection
                : projections
        ) {

            /*
             * 같은 Category가 JOIN 결과에 여러 번 등장하므로
             * 최초 1회만 CategoryAccumulator 생성.
             */
            CategoryAccumulator category =
                    categoryMap.computeIfAbsent(
                            projection.getEventCategoryId(),
                            ignored ->
                                    createCategoryAccumulator(
                                            projection
                                    )
                    );


            /*
             * 해당 Category에 현재 row의
             * Souvenir 추가.
             */
            category.addSouvenir(
                    createSouvenirOption(
                            projection
                    )
            );
        }


        List<RegistrationOptionResponse.EventCategoryOption>
                categories =
                categoryMap.values()
                        .stream()
                        .map(
                                CategoryAccumulator::toResponse
                        )
                        .toList();


        return new RegistrationOptionResponse(
                categories
        );
    }


    private CategoryAccumulator createCategoryAccumulator(
            RegistrationOptionProjection projection
    ) {

        CategoryNameParts categoryNameParts =
                parseCategoryName(
                        projection.getEventCategoryName()
                );


        return new CategoryAccumulator(
                projection.getEventCategoryOrder(),
                projection.getEventCategoryId(),
                categoryNameParts.distance(),
                categoryNameParts.categoryName(),
                projection.getEventCategoryIsActive(),
                projection.getEventCategoryAmount()
        );
    }


    private RegistrationOptionResponse.SouvenirOption
    createSouvenirOption(
            RegistrationOptionProjection projection
    ) {

        return new RegistrationOptionResponse.SouvenirOption(
                projection.getSouvenirOrder(),
                projection.getSouvenirId(),
                projection.getSouvenirName(),
                parseSizes(
                        projection.getSouvenirSizes()
                )
        );
    }


    /*
     * EventCategory.name 파싱.
     *
     * MarvelRun 신규 형태:
     *
     * 10km | 일반
     *
     * 과거 KMA 데이터 호환:
     *
     * 1 | 10km | 일반
     *
     * 두 형태 모두 받을 수 있게 구성.
     *
     * order 자체는 절대 name에서 결정하지 않으며,
     * DB의 sort_order가 Source of Truth.
     */
    private CategoryNameParts parseCategoryName(
            String eventCategoryName
    ) {

        if (
                eventCategoryName == null
                        || eventCategoryName.isBlank()
        ) {

            return new CategoryNameParts(
                    "",
                    ""
            );
        }


        String[] parts =
                eventCategoryName.split(
                        "\\s*\\|\\s*"
                );


        /*
         * 과거 KMA 형식:
         *
         * 1 | 10km | 일반
         */
        if (
                parts.length >= 3
                        && isNumber(parts[0])
        ) {

            return new CategoryNameParts(
                    parts[1].trim(),
                    parts[2].trim()
            );
        }


        /*
         * MarvelRun 신규 형식:
         *
         * 10km | 일반
         */
        String distance =
                parts.length >= 1
                        ? parts[0].trim()
                        : "";


        String categoryName =
                parts.length >= 2
                        ? parts[1].trim()
                        : distance;


        return new CategoryNameParts(
                distance,
                categoryName
        );
    }


    private boolean isNumber(
            String value
    ) {

        if (
                value == null
                        || value.isBlank()
        ) {
            return false;
        }


        try {

            Long.parseLong(
                    value.trim()
            );

            return true;

        } catch (
                NumberFormatException ignored
        ) {

            return false;
        }
    }


    /*
     * DB:
     *
     * S|M|L|XL
     *
     * API:
     *
     * ["S", "M", "L", "XL"]
     *
     * null / blank이면 FREE.
     */
    private List<String> parseSizes(
            String sizes
    ) {

        if (
                sizes == null
                        || sizes.isBlank()
        ) {

            return List.of(
                    "FREE"
            );
        }


        return Arrays.stream(
                        sizes.split("\\|")
                )
                .map(
                        String::trim
                )
                .filter(
                        size -> !size.isBlank()
                )
                .toList();
    }


    /*
     * Flat Projection 여러 row를
     * EventCategory 하나로 묶기 위한
     * Assembler 내부 전용 임시 객체.
     */
    private static class CategoryAccumulator {

        private final long order;

        private final String categoryId;

        private final String distance;

        private final String categoryName;

        private final Boolean isActive;

        private final BigDecimal amount;


        /*
         * Repository의 souvenir.order ASC 결과를
         * 그대로 보존하기 위해 LinkedHashMap 사용.
         *
         * 동시에 동일 souvenir가 중복되는 상황도 방지.
         */
        private final Map<
                String,
                RegistrationOptionResponse.SouvenirOption
                > souvenirMap =
                new LinkedHashMap<>();


        private CategoryAccumulator(
                long order,
                String categoryId,
                String distance,
                String categoryName,
                Boolean isActive,
                BigDecimal amount
        ) {

            this.order = order;
            this.categoryId = categoryId;
            this.distance = distance;
            this.categoryName = categoryName;
            this.isActive = isActive;
            this.amount = amount;
        }


        private void addSouvenir(
                RegistrationOptionResponse.SouvenirOption
                        souvenir
        ) {

            souvenirMap.putIfAbsent(
                    souvenir.souvenirId(),
                    souvenir
            );
        }


        private RegistrationOptionResponse.EventCategoryOption
        toResponse() {

            return new RegistrationOptionResponse
                    .EventCategoryOption(
                    order,
                    categoryId,
                    distance,
                    categoryName,
                    isActive,
                    amount,
                    List.copyOf(
                            souvenirMap.values()
                    )
            );
        }
    }


    private record CategoryNameParts(

            String distance,

            String categoryName
    ) {
    }
}